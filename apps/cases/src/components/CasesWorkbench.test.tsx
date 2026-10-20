import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { LiveConnection, LiveProvider } from '@fp/shared';
import { CasesWorkbench } from './CasesWorkbench';
let commands: Record<string,unknown>[]=[];
let status='APPLIED';let mode='live';let roles=['case_reviewer','inspector_demo','lab_demo','exporter'];
let additionalRecords:Record<string,unknown[]>={};let stationId:string|null='test';
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
  act(()=>{Socket.last.onopen?.();Socket.last.onmessage?.({data:JSON.stringify({schema_version:'fp/1',message_type:'hello',server_epoch:'epoch',run_id:'run-1',cursor:1,sim_time_s:1,server_time_utc:base.created_utc,payload:{mode,after:null,reload_snapshot:false,kept_events:1}})});});
  await waitFor(()=>expect(screen.getByText('Teaching intake')).toBeTruthy());return c;
}
beforeEach(()=>{
  commands=[];status='APPLIED';mode='live';roles=['case_reviewer','inspector_demo','lab_demo','exporter'];additionalRecords={};stationId='test';
  vi.stubGlobal('fetch',vi.fn(async(input:string,init?:RequestInit)=>{
    if(input.includes('/api/commands')) {commands.push(JSON.parse(String(init?.body)));return new Response(JSON.stringify({receipt_id:'receipt',command_id:'command',status,reason_code:status==='REJECTED'?'STALE_REVISION':null,reason_text:status==='REJECTED'?'Case revision changed':null,record_refs:[]}));}
    const body=input.includes('/api/snapshot')?{run_id:'run-1',cursor:1,run:{...base,id:'run-1',preset_id:'SC1-NIGHT-PLUME',state:'RUNNING'},records:{...records,...additionalRecords},latest_readings:[]}:input.includes('/api/session')?{signed_in:true,station_id:stationId,roles}:[];
    return new Response(JSON.stringify(body));
  }));
});

// These are approved wire-protocol fixtures, not backend integration evidence.
const media={...base,id:'media-1',revision:3,case_id:'case-1',media_type:'FILTER',material:'SYNTHETIC_UNCLASSIFIED_AEROSOL',method_id:'TEACH-AEROSOL-S-1',size_class:'UNCLASSIFIED',container:'Filter cassette',seal_id:'filter-seal',location_label:'Teaching collection point',collection:{start_sim_s:0,end_sim_s:600,valid_s:600,flow_L_min:2,status:'COMPLETE'},collection_computed:{volume_L:20,volume_m3:.02,computed_decimal:{volume_L:'20',volume_m3:'0.02'},calculation_precision:'DECIMAL128_HALF_EVEN',computed_reasons:{}},collection_reasons:[],attempt_ref:null,attempt_ref_reason:'FIELD_AEROSOL_CAPTURE_NOT_AVAILABLE',origin:'SYNTHETIC_TEACHING',input_basis:'HUMAN_RECORDED_SYNTHETIC_TEACHING',authority_basis:'INSPECTOR_DEMO',collector_id:'inspector@test',current_holder:'lab@test',current_media_custody_event_id:'mc-1',seal_state:'INTACT',custody_exceptions:[],recorded_utc:base.created_utc,recorded_sim_time_s:600,recorded_sim_time_reason:null,case_ref:{type:'case',id:'case-1',revision:4}};
const mediaLab={...base,id:'media-lab-1',case_id:'case-1',media_id:'media-1',method_id:'TEACH-AEROSOL-S-1',origin:'SYNTHETIC_TEACHING',input_basis:'HUMAN_RECORDED_SYNTHETIC_TEACHING',qc_inputs:{gross_ug:.01,blank_ug:.02,dup_a_ug:.9,dup_b_ug:1.1,spike_added_ug:1,spike_recovered_ug:1},raw:{gross_ug:.01,blank_ug:.02},computed:{net_ug:-.01,concentration_ug_m3:-.5,rpd_pct:20,recovery_pct:100},computed_decimal:{net_ug:'-0.01',concentration_ug_m3:'-0.5'},calculation_precision:'DECIMAL128_HALF_EVEN',computed_reasons:{},qc_status:'PASS',qc_checks:{blank:{status:'PASS',reasons:[]}},qc_reasons:[],diagnostics:['NEGATIVE_CORRECTED_MASS'],reporting:'BELOW_LOQ',reporting_reason:null,result_use:'LIMITED_BELOW_LOQ',hold_time_s:20,hold_time_reason:null,collection_end_sim_s:600,analysis_sim_time_s:620,analysis_sim_time_reason:null,media_ref:{type:'media',id:'media-1',revision:3},case_ref:{type:'case',id:'case-1',revision:4},custody_ref:{type:'media_custody_event',id:'mc-1',revision:1},method_limits:{loq_ug:.1},analyzed_by:'lab@test',limitations:['Synthetic teaching filter only; no plume causation.']};
function withMedia(){additionalRecords={media:[media],media_lab_result:[mediaLab],media_custody_event:[]};}
function fillCollection(){
  for(const [label,value] of [['Aerosol container','Cassette'],['Aerosol seal identifier','Seal-A'],['Aerosol collection location','Teaching north point'],['Collection start (sim s)','0'],['Collection end (sim s)','600'],['Valid collection duration (s)','600'],['Recorded flow (L/min)','2']])fireEvent.change(screen.getByLabelText(label),{target:{value}});
}
describe('approved aerosol and signed evidence interfaces',()=>{
  it('sends only explicitly entered collection inputs with the current case revision, never a computed volume or fabricated attempt',async()=>{
    await mount();fillCollection();fireEvent.click(screen.getByRole('button',{name:'Record aerosol filter'}));await waitFor(()=>expect(commands).toHaveLength(1));
    expect(commands[0]).toMatchObject({command_type:'create_media',expected_revision:4,run_id:'run-1',payload:{case_id:'case-1',media_type:'FILTER',material:'SYNTHETIC_UNCLASSIFIED_AEROSOL',method_id:'TEACH-AEROSOL-S-1',container:'Cassette',seal_id:'Seal-A',location_label:'Teaching north point',collection:{start_sim_s:0,end_sim_s:600,valid_s:600,flow_L_min:2,status:'COMPLETE'}}});
    expect(Object.keys(commands[0].payload as object).sort()).toEqual(['case_id','collection','container','location_label','material','media_type','method_id','seal_id']);
  });
  it('sends aerosol lab masses in ug and leaves missing entries null rather than using fuel percent inputs',async()=>{
    withMedia();await mount();fireEvent.change(screen.getByLabelText('Aerosol gross sulfur assay (ug)'),{target:{value:'0.1'}});fireEvent.change(screen.getByLabelText('Aerosol duplicate A (ug)'),{target:{value:'0.9'}});
    fireEvent.click(screen.getByRole('button',{name:'Request aerosol lab evaluation'}));await waitFor(()=>expect(commands).toHaveLength(1));
    expect(commands[0]).toMatchObject({command_type:'request_media_lab',expected_revision:3,payload:{media_id:'media-1',method_id:'TEACH-AEROSOL-S-1',qc_inputs:{gross_ug:.1,blank_ug:null,dup_a_ug:.9,dup_b_ug:null,spike_added_ug:null,spike_recovered_ug:null}}});
  });
  it('appends custody to the selected media identity and revision without touching a fuel specimen',async()=>{
    withMedia();await mount();fireEvent.change(screen.getByLabelText('Aerosol transfer to human holder'),{target:{value:'inspector@test'}});fireEvent.change(screen.getByLabelText('Aerosol event time UTC'),{target:{value:'2026-10-20T10:05:00Z'}});
    fireEvent.click(screen.getByRole('button',{name:'Append aerosol custody event'}));await waitFor(()=>expect(commands).toHaveLength(1));
    expect(commands[0]).toMatchObject({command_type:'append_media_custody',expected_revision:3,payload:{media_id:'media-1',to_holder:'inspector@test',seal_state:'INTACT',event_time_utc:'2026-10-20T10:05:00Z',exceptions:[]}});
  });
  it('keeps negative net mass, below-LOQ limits and the absent Field link visible without automatically saving a finding',async()=>{
    withMedia();await mount();expect(document.body.textContent).toContain('LIMITED_BELOW_LOQ');expect(document.body.textContent).toContain('NEGATIVE_CORRECTED_MASS');expect(document.body.textContent).toContain('-0.01');expect(document.body.textContent).toContain('FIELD_AEROSOL_CAPTURE_NOT_AVAILABLE');expect(commands).toHaveLength(0);
  });
  it('lets a person cite the exact media-lab revision in an inconclusive finding',async()=>{
    withMedia();await mount();fireEvent.click(screen.getByRole('checkbox',{name:/media_lab_result media-lab-1 revision 1/}));fireEvent.change(screen.getByLabelText('Finding limitations (one per line)'),{target:{value:'Below reporting limit; no source claim'}});
    fireEvent.click(screen.getByRole('button',{name:'Save human finding'}));await waitFor(()=>expect(commands).toHaveLength(1));expect(commands[0]).toMatchObject({command_type:'revise_finding',expected_revision:4,payload:{outcome:'INCONCLUSIVE',cited_refs:[{type:'media_lab_result',id:'media-lab-1',revision:1}]}});
  });
  it.each(['0.099999999999999999999','1e309','6e-324'])('refuses %s rather than silently changing a decimal QC input',async value=>{
    withMedia();await mount();fireEvent.change(screen.getByLabelText('Aerosol gross sulfur assay (ug)'),{target:{value}});
    expect((screen.getByRole('button',{name:'Request aerosol lab evaluation'}) as HTMLButtonElement).disabled).toBe(true);expect(document.body.textContent).toContain('cannot send this decimal unchanged');expect(commands).toHaveLength(0);
  });
  it('displays signature validity and untrusted key separately from passing integrity, including the complete fingerprint',async()=>{
    additionalRecords={export:[{...records.export[0],signature:{path:'signature.json',length:500,sha256:'signature-hash',algorithm:'Ed25519',key_fingerprint_sha256:'a'.repeat(64)}}],verification_report:[{...records.verification_report[0],axes:{...records.verification_report[0].axes,key_trust:'UNTRUSTED_KEY'},signature_check:{validity:'VALID',trust:'UNTRUSTED',trust_source:'NONE',key_fingerprint_sha256:'a'.repeat(64),reason:'Key is not in external trust file'}}]};
    await mount();expect(document.body.textContent).toContain('Ed25519');expect(screen.getByText('Signature validity: VALID')).toBeTruthy();expect(screen.getByText('External key trust: UNTRUSTED')).toBeTruthy();expect(document.body.textContent).toContain('a'.repeat(64));expect(document.body.textContent).toContain('UNTRUSTED_KEY');expect(document.body.textContent).toContain('Key is not in external trust file');
  });
  it('links an authorized ZIP download to the exact export and run, and removes the link when disconnected',async()=>{
    await mount();expect(screen.getByRole('link',{name:'Download ZIP export-1'}).getAttribute('href')).toContain('/api/exports/export-1/pack.zip?run_id=run-1');
    act(()=>Socket.last.close());await waitFor(()=>expect(screen.queryByRole('link',{name:'Download ZIP export-1'})).toBeNull());
  });
  it('does not offer ZIP access to a non-station session even if it claims exporter',async()=>{
    stationId=null;await mount();expect(screen.queryByRole('link',{name:'Download ZIP export-1'})).toBeNull();
  });
  it('keeps aerosol mutation and download controls read-only in replay even when the socket is live',async()=>{
    mode='replay';withMedia();await mount();fillCollection();
    expect((screen.getByRole('button',{name:'Record aerosol filter'}) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button',{name:'Request aerosol lab evaluation'}) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByRole('link',{name:'Download ZIP export-1'})).toBeNull();expect(commands).toHaveLength(0);
  });
  it('keeps aerosol mutations disabled for a reviewer without inspector or lab roles',async()=>{
    roles=['case_reviewer'];withMedia();await mount();fillCollection();
    fireEvent.click(screen.getByRole('button',{name:'Record aerosol filter'}));fireEvent.click(screen.getByRole('button',{name:'Request aerosol lab evaluation'}));expect(commands).toHaveLength(0);
  });
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
    act(()=>Socket.last.close());fireEvent.click(screen.getByRole('button',{name:'Create case intake'}));expect(commands).toHaveLength(0);
  });
  it('shows stale rejection and does not automatically repeat the command',async()=>{
    status='REJECTED';await mount();fireEvent.click(screen.getByRole('button',{name:'Verify export export-1'}));
    await waitFor(()=>expect(screen.getByRole('alert').textContent).toContain('Case revision changed'));expect(commands).toHaveLength(1);
  });
});
