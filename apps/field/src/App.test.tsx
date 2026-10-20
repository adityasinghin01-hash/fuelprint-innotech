import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LiveConnection } from '@fp/shared';
import type { TickMessage } from '@fp/shared';
import { App } from './App';
class Socket {
  static last: Socket; readyState = 1;
  onopen: (() => void) | null = null; onmessage: ((m: {data: string}) => void) | null = null;
  onclose: (() => void) | null = null; onerror = null;
  constructor(readonly url: string) { Socket.last = this; }
  push(m: unknown) { this.onmessage?.({data: JSON.stringify(m)}); }
  close() { this.readyState = 3; this.onclose?.(); }
}
const tick = (time=12, east=10, run='run-1'): TickMessage => ({ schema_version:'fp/1',message_type:'tick',run_id:run,
  server_epoch:'epoch-1',cursor:3,sim_time_s:time,server_time_utc:'2026-10-20T10:00:00Z',
  payload:{wind:{from_deg:270,speed_m_s:3},time_scale:1,
    vehicles:[{ vehicle_id:'drone-1',label:'DRONE-1',kind:'DRONE',pose:{east,north:20,up:35,yaw_deg:90},
      velocity:{east:1,north:0,up:0},inlet:{east:east+.4,north:20,up:34.6},energy_wh:60,stage:'OUTBOUND',lease_holder:'autopilot',pilot:'AUTOPILOT'}],
    latest_readings:[{sensor:'N2',channel:'SO2_ppb',value:61.2,quality:['OK'],sim_time_s:time}]} });
let commands: unknown[]=[];
let records: Record<string,unknown[]>={};
function mount() {
  const connection=new LiveConnection({socketFactory:u=>new Socket(u) as unknown as WebSocket});
  render(<App connection={connection} sceneEnabled={false}/>);
  act(()=>{ Socket.last.onopen?.(); Socket.last.push({...tick(),message_type:'hello',payload:{mode:'live',after:null,reload_snapshot:false,kept_events:1}}); Socket.last.push(tick()); });
  return connection;
}
beforeEach(()=>{
  commands=[];records={};
  vi.stubGlobal('fetch',vi.fn(async (input:string, init?:RequestInit)=>{
    if (input.includes('/api/commands')) { commands.push(JSON.parse(String(init?.body))); return new Response(JSON.stringify({status:'REJECTED',reason_code:'INVALID',reason_text:'Server refused test command',record_refs:[]})); }
    const body=input.includes('/api/snapshot') ? {run_id:'run-1',cursor:3,run:{id:'run-1',run_id:'run-1',revision:1,preset_id:'SC1-NIGHT-PLUME',state:'RUNNING'},records,latest_readings:[]}
      : input.includes('/api/session/stations') ? [] : input.includes('/api/session') ? {signed_in:true,station_id:'demo',roles:['presenter','field_operator','qc_reviewer']} : [];
    return new Response(JSON.stringify(body));
  }));
});
afterEach(()=>{cleanup();vi.useRealTimers();vi.unstubAllGlobals();});
describe('server connected field view',()=>{
  it('requires explicit human channel decisions and prevents promotion of a rejected precheck',async()=>{
    records={attempt:[{id:'attempt-1',run_id:'run-1',revision:5,created_utc:'2026-10-20T10:00:00Z',created_by:'automation',mission_id:'mission-1',method_id:'TEACH-NODE-ONLINE-2',state:'FAILED',stages:[],channels:[
      {channel:'SO2_ppb',status:'REJECTED',valid_seconds:0,volume_L:null,reasons:['INVALID_FLOW']},
      {channel:'CO2_ppm',status:'VALID',valid_seconds:30,volume_L:1,reasons:[]}
    ]}]};
    mount();await waitFor(()=>expect(screen.getByLabelText('Decision for SO2_ppb')).toBeTruthy());
    expect((within(screen.getByLabelText('Decision for SO2_ppb')).getByRole('option',{name:'VALID'}) as HTMLOptionElement).disabled).toBe(true);
    expect((screen.getByRole('button',{name:'Save human capture review'}) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Decision for SO2_ppb'),{target:{value:'REJECTED'}});
    fireEvent.change(screen.getByLabelText('Decision for CO2_ppm'),{target:{value:'VALID'}});
    fireEvent.click(screen.getByRole('button',{name:'Save human capture review'}));
    await waitFor(()=>expect(commands).toHaveLength(1));
    expect(commands[0]).toMatchObject({command_type:'review_capture',expected_revision:5,run_id:'run-1',payload:{attempt_id:'attempt-1',channel_decisions:[{channel:'SO2_ppb',decision:'REJECTED',reasons:[]},{channel:'CO2_ppm',decision:'VALID',reasons:[]}]}});
  });

  it('shows matching run/pose/inlet and actual tick reading; new frames update both',async()=>{
    mount(); await waitFor(()=>expect(screen.getByTestId('frame-identity').textContent).toContain('run-1'));
    expect(screen.getByTestId('vehicle-pose').textContent).toContain('10, 20, 35');
    expect(screen.getByTestId('vehicle-inlet').textContent).toContain('10.4, 20, 34.6');
    expect(screen.getByRole('table').textContent).toContain('61.2');
    expect(screen.getByRole('table').textContent).toContain('origin unknown');
    act(()=>Socket.last.push(tick(13,15)));
    expect(screen.getByTestId('vehicle-pose').textContent).toContain('15, 20, 35');
    expect(screen.getByTestId('frame-identity').textContent).toContain('13');
  });
  it('drops run mismatches, hides old frames after reconnect until a fresh tick',async()=>{
    mount(); await waitFor(()=>expect(screen.queryByTestId('vehicle-pose')).toBeTruthy());
    act(()=>Socket.last.push(tick(13,999,'run-2')));
    expect(screen.queryByTestId('vehicle-pose')).toBeNull();
    act(()=>Socket.last.push(tick(13,15)));
    expect(screen.getByTestId('vehicle-pose').textContent).toContain('15, 20, 35');
    act(()=>Socket.last.close());
    expect((screen.getByRole('button',{name:'Pause run'}) as HTMLButtonElement).disabled).toBe(true);
  });
  it('keeps null values unknown and supports an empty vehicle list',async()=>{
    mount(); await waitFor(()=>expect(screen.queryByTestId('vehicle-pose')).toBeTruthy());
    const t=tick(13);t.payload.vehicles=[];t.payload.latest_readings[0].value=null;t.payload.latest_readings[0].quality=['INVALID_FLOW'];
    act(()=>Socket.last.push(t));
    expect(screen.queryByTestId('vehicle-pose')).toBeNull();
    expect(screen.getByText(/No vehicle in this server frame/)).toBeTruthy();
    expect(screen.getByRole('table').textContent).toContain('unknown');expect(screen.getByRole('table').textContent).toContain('INVALID_FLOW');
  });
  it('uses shared revision-aware commands and displays the server rejection',async()=>{
    mount(); await waitFor(()=>expect((screen.getByRole('button',{name:'Pause run'}) as HTMLButtonElement).disabled).toBe(false));
    act(()=>screen.getByRole('button',{name:'Pause run'}).click());
    await waitFor(()=>expect(screen.getByRole('alert').textContent).toContain('Server refused test command'));
    expect(commands).toHaveLength(1);expect(commands[0]).toMatchObject({command_type:'pause_run',run_id:'run-1',expected_revision:1,payload:{run_id:'run-1'}});
  });
});
