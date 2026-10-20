import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { LiveConnection, LiveProvider } from '@fp/shared';
import { CasesWorkbench } from './CasesWorkbench';
let commands: Record<string,unknown>[]=[];
let status='APPLIED';let mode='live';let roles=['case_reviewer','inspector_demo','lab_demo','exporter'];
const base={run_id:'run-1',revision:1,created_utc:'2026-10-20T10:00:00Z',created_by:'case_reviewer@test'};
const records={
  screening_record:[{...base,id:'screen-1',mission_id:'mission-1',attempt_id:'attempt-1',channel:'SO2_ppb',named_use:'SCREENING',decision:'REJECTED',reasons:['Invalid flow'],summary:{}}],
  case:[{...base,id:'case-1',revision:4,investigation_id:'investigation-1',screening_record_ids:['screen-1'],purpose:'Teaching intake',state:'LAB'}],
  specimen:[{...base,id:'specimen-1',revision:2,case_id:'case-1',site_id:'S02',material:'SYNTHETIC_COAL_COKE_LIKE',method_id:'TEACH-FUEL-S-1',container:'Jar',seal_id:'seal-1',mass_mg:100,current_holder:'lab_demo@test',authority_basis:'INSPECTOR_DEMO'}],
  custody_event:[],lab_result:[{...base,id:'lab-1',specimen_id:'specimen-1',method_id:'TEACH-FUEL-S-1',raw:{gross_ug:10,blank_ug:1,mass_mg:100,dup_a_pct:1,dup_b_pct:1,spike_added_ug:5,spike_recovered_ug:5},computed:{net_ug:9,percent_w_w:.009,rpd_pct:0,recovery_pct:100},qc_status:'PASS',qc_reasons:[],reporting:'REPORTED'}],
  finding:[],export:[{...base,id:'export-1',case_id:'case-1',case_revision:3,manifest_sha256:'abc',members:[{path:'case.json',length:10,sha256:'def'}],signature:null,state:'SEALED'}],
  verification_report:[{...base,id:'report-1',export_id:'export-1',axes:{integrity:'PASS',key_trust:'NOT_SIGNED',lineage:'PASS',trusted_time:'NOT_ESTABLISHED',custody_documentation:'DOCUMENTED',measurement_qualification:'SYNTHETIC_SCOPE_ONLY',legal_suitability:'NOT_ASSESSED'},reasons:['No signature']}],
};
class Socket {
  static last:Socket;readyState=1;onopen:(()=>void)|null=null;onmessage:((m:{data:string})=>void)|null=null;onclose:(()=>void)|null=null;onerror=null;
  constructor(){Socket.last=this;} close(){this.readyState=3;this.onclose?.();}
}
async function mount(){
  const c=new LiveConnection({socketFactory:()=>new Socket() as unknown as WebSocket});
  render(<LiveProvider connection={c}><CasesWorkbench/></LiveProvider>);
  Socket.last.onopen?.();Socket.last.onmessage?.({data:JSON.stringify({schema_version:'fp/1',message_type:'hello',server_epoch:'epoch',run_id:'run-1',cursor:1,sim_time_s:1,server_time_utc:base.created_utc,payload:{mode,after:null,reload_snapshot:false,kept_events:1}})});
  await waitFor(()=>expect(screen.getByText('Teaching intake')).toBeTruthy());return c;
}
beforeEach(()=>{
  commands=[];status='APPLIED';mode='live';roles=['case_reviewer','inspector_demo','lab_demo','exporter'];
  vi.stubGlobal('fetch',vi.fn(async(input:string,init?:RequestInit)=>{
    if(input.includes('/api/commands')) {commands.push(JSON.parse(String(init?.body)));return new Response(JSON.stringify({receipt_id:'receipt',command_id:'command',status,reason_code:status==='REJECTED'?'STALE_REVISION':null,reason_text:status==='REJECTED'?'Case revision changed':null,record_refs:[]}));}
    const body=input.includes('/api/snapshot')?{run_id:'run-1',cursor:1,run:{...base,id:'run-1',preset_id:'SC1-NIGHT-PLUME',state:'RUNNING'},records,latest_readings:[]}:input.includes('/api/session')?{signed_in:true,station_id:'test',roles}:[];
    return new Response(JSON.stringify(body));
  }));
});
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
describe('minimal cases workbench',()=>{
  it('keeps rejected screening, synthetic scope, unsigned trust and all verification axes visible',async()=>{
    await mount();expect(document.body.textContent).toContain('REJECTED');expect(document.body.textContent).toContain('Invalid flow');
    expect(document.body.textContent).toContain('SYNTHETIC_TEACHING');expect(document.body.textContent).toContain('NOT_SIGNED');
    expect(document.body.textContent).toContain('NOT_ASSESSED');expect(document.body.textContent).toContain('NOT_ESTABLISHED');
  });
  it('creates an intake using explicitly selected screening ids',async()=>{
    await mount();fireEvent.click(screen.getByRole('checkbox',{name:/screen-1.*SO2_ppb/}));fireEvent.change(screen.getByLabelText('Case purpose'),{target:{value:'Declared teaching case'}});
    fireEvent.click(screen.getByRole('button',{name:'Create case intake'}));await waitFor(()=>expect(commands).toHaveLength(1));
    expect(commands[0]).toMatchObject({command_type:'create_case',expected_revision:null,run_id:'run-1',payload:{screening_record_ids:['screen-1'],purpose:'Declared teaching case'}});
  });
  it('submits raw lab teaching inputs without computing the result or replacing blank fields with zero',async()=>{
    await mount();fireEvent.change(screen.getByLabelText('Gross sulfur (ug)'),{target:{value:'12.5'}});
    fireEvent.click(screen.getByRole('button',{name:'Request fuel lab evaluation'}));await waitFor(()=>expect(commands).toHaveLength(1));
    expect(commands[0]).toMatchObject({command_type:'request_lab',expected_revision:2,payload:{specimen_id:'specimen-1',method_id:'TEACH-FUEL-S-1',qc_inputs:{gross_ug:12.5,blank_ug:null,dup_a_pct:null,dup_b_pct:null,spike_added_ug:null,spike_recovered_ug:null}}});
  });
  it('verifies original evidence and tampers only a named copy using current export revision',async()=>{
    await mount();fireEvent.click(screen.getByRole('button',{name:'Verify export export-1'}));await waitFor(()=>expect(commands).toHaveLength(1));
    expect(commands[0]).toMatchObject({command_type:'verify_export',expected_revision:1,payload:{export_id:'export-1'}});
    await waitFor(()=>expect((screen.getByRole('button',{name:'Make tampered COPY of export-1'}) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button',{name:'Make tampered COPY of export-1'}));await waitFor(()=>expect(commands).toHaveLength(2));
    expect(commands[1]).toMatchObject({command_type:'tamper_copy',expected_revision:1,payload:{export_id:'export-1',member_path:'case.json'}});
  });
  it('blocks export actions for a wrong role and after disconnect',async()=>{
    roles=['case_reviewer'];await mount();expect((screen.getByRole('button',{name:'Verify export export-1'}) as HTMLButtonElement).disabled).toBe(true);
    Socket.last.close();fireEvent.click(screen.getByRole('button',{name:'Create case intake'}));expect(commands).toHaveLength(0);
  });
  it('shows stale rejection and does not automatically repeat the command',async()=>{
    status='REJECTED';await mount();fireEvent.click(screen.getByRole('button',{name:'Verify export export-1'}));
    await waitFor(()=>expect(screen.getByRole('alert').textContent).toContain('Case revision changed'));expect(commands).toHaveLength(1);
  });
});
