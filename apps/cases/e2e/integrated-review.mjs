// Opt-in destructive-to-test-run rehearsal. Use only a dedicated disposable real-mode server.
// No seeded evidence: public commands drive the real survey, approval, flight and human QC path.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

if (process.env.FP_E2E_MUTATE !== '1') throw new Error('Set FP_E2E_MUTATE=1 only for a disposable local review server.');
const base = process.env.FP_E2E_URL ?? 'http://127.0.0.1:8081';
if (!['127.0.0.1','localhost'].includes(new URL(base).hostname)) throw new Error('Local review server only.');
const preset = process.env.FP_E2E_PRESET ?? 'SC1-NIGHT-PLUME';
const out = path.resolve(process.env.FP_E2E_OUT ?? 'apps/cases/.taste-skill/integrated-runtime');
await fs.mkdir(out,{recursive:true});
const trace = {preset,scope:'Scripted human steps with real production handlers/physics. Synthetic teaching inputs, not physical validation.',receipts:[],checks:[]};
let runId = null;
async function request(route, options={}) {
  const response = await fetch(base+route,options);
  const body = await response.json();
  assert(response.ok,JSON.stringify(body)); return body;
}
async function station(station_id,pin) {
  const response = await fetch(base+'/api/session/station',{method:'POST',headers:{'content-type':'application/json',origin:base},body:JSON.stringify({station_id,pin})});
  assert(response.ok,'Station login failed'); return response.headers.get('set-cookie').split(';')[0];
}
const ops = await station('laptop-1','1111'), field = await station('laptop-2','2222'), lab = await station('laptop-3','3333'), inspector = await station('laptop-4','4444');
async function command(cookie,command_type,payload,expected_revision=null) {
  const receipt = await request('/api/commands',{method:'POST',headers:{'content-type':'application/json',origin:base,cookie},body:JSON.stringify({schema_version:'fp/1',command_id:randomUUID(),command_type,run_id:runId,expected_revision,payload})});
  trace.receipts.push({command_type,receipt});
  assert.equal(receipt.status,'APPLIED',`${command_type}: ${receipt.reason_code} ${receipt.reason_text}`); return receipt;
}
const records = type => request(`/api/records/${type}?run_id=${runId}`);
const current = (type,id) => request(`/api/records/${type}/${id}`);
async function from(receipt,type) {const ref=receipt.record_refs.find(item=>item.type===type);assert(ref,`Missing ${type} ref`);return current(type,ref.id);}
async function until(label,read,ms=360000) {
  const deadline=Date.now()+ms;
  while(Date.now()<deadline) {const value=await read();if(value)return value;await new Promise(resolve=>setTimeout(resolve,1500));}
  throw new Error(`Timed out: ${label}`);
}
const ref = (type,record) => ({type,id:record.id,revision:record.revision});
try {
  for(const setting of ['auto_propose','auto_autopilot','auto_capture','auto_seal','auto_alerts','auto_retrain']) await command(ops,'change_automation',{setting,value:false});
  const run=await from(await command(ops,'reset_run',{preset_id:preset,mode:'BLIND'}),'run');runId=run.id;trace.run_id=runId;
  console.log('Real run started:',preset,runId);
  const action_id=preset.startsWith('SC3')?'SURVEY-DRONE-280-140-45':'SURVEY-DRONE-0-100-35';
  const survey=await from(await command(ops,'schedule_survey',{action_id,reason:'Independent public-catalogue survey for local Cases integration review; no planner benefit claimed.'}),'survey');
  const mission=await from(await command(ops,'propose_mission',{survey_id:survey.id,action_id},survey.revision),'mission');
  await command(ops,'approve_mission',{mission_id:mission.id,dependency_hash:mission.dependency_hash},mission.revision);
  await command(field,'start_autopilot',{mission_id:mission.id,vehicle_id:mission.vehicle_id});
  console.log('Scripted human approval complete; waiting for actual flight.');
  await until('actual arrival',async()=>{const value=await current('mission',mission.id);return value.state==='AT_TARGET'&&value;});
  await command(field,'start_capture',{mission_id:mission.id,method_id:mission.method_id,channel_ids:mission.channel_ids});
  const attempt=await until('capture complete',async()=>(await records('attempt')).find(item=>item.mission_id===mission.id&&item.state!=='COLLECTING'));
  await command(field,'review_capture',{attempt_id:attempt.id,channel_decisions:attempt.channels.map(item=>({channel:item.channel,decision:item.status??'REJECTED',reasons:['SCRIPTED_LOCAL_REVIEW_CONFIRMS_SERVER_PRECHECK']}))},attempt.revision);
  const screening=(await records('screening_record')).filter(item=>item.attempt_id===attempt.id);
  assert(screening.length>0);
  await command(ops,'pause_run',{});
  let caseRecord=await from(await command(lab,'create_case',{screening_record_ids:screening.map(item=>item.id),purpose:`Local integrated ${preset} evidence review`}), 'case');
  const specimen=await from(await command(inspector,'create_specimen',{case_id:caseRecord.id,site_id:'S02',material:'SYNTHETIC_COAL_COKE_LIKE',method_id:'TEACH-FUEL-S-1',container:'Teaching review jar',seal_id:'LOCAL-REVIEW-SEAL',mass_mg:100},caseRecord.revision),'specimen');
  const session=await request('/api/session',{headers:{cookie:lab}});
  await command(inspector,'append_custody',{specimen_id:specimen.id,to_holder:session.actor_label,seal_state:'INTACT',event_time_utc:new Date().toISOString(),exceptions:[]},specimen.revision);
  const held=await current('specimen',specimen.id);
  const result=await from(await command(lab,'request_lab',{specimen_id:specimen.id,method_id:'TEACH-FUEL-S-1',qc_inputs:{gross_ug:2010,blank_ug:10,dup_a_pct:2,dup_b_pct:2.2,spike_added_ug:100,spike_recovered_ug:90}},held.revision),'lab_result');
  assert.equal(result.qc_status,'PASS');
  caseRecord=await current('case',caseRecord.id);
  // Explicit human test choice: inconclusive retains uncertainty, never forces the scenario narrative.
  const finding=await from(await command(lab,'revise_finding',{case_id:caseRecord.id,outcome:'INCONCLUSIVE',cited_refs:[...screening.map(item=>ref('screening_record',item)),ref('lab_result',result)],alternatives:['Other explanations remain possible.'],limitations:['Synthetic teaching measurements only; specimen QC does not identify a plume source.']},caseRecord.revision),'finding');
  caseRecord=await current('case',caseRecord.id);
  const pack=await from(await command(inspector,'seal_export',{case_id:caseRecord.id,case_revision:caseRecord.revision},caseRecord.revision),'export');
  const report=await from(await command(inspector,'verify_export',{export_id:pack.id},pack.revision),'verification_report');
  assert.equal(report.axes.integrity,'PASS');assert.equal(report.signature_check.validity,'VALID');assert.equal(report.signature_check.trust,'TRUSTED');
  const copy=await from(await command(inspector,'tamper_copy',{export_id:pack.id,member_path:pack.members[0].path},pack.revision),'export');
  const copyReport=await from(await command(inspector,'verify_export',{export_id:copy.id},copy.revision),'verification_report');assert.equal(copyReport.axes.integrity,'FAIL');
  const originalRecheck=await from(await command(inspector,'verify_export',{export_id:pack.id},pack.revision),'verification_report');assert.equal(originalRecheck.axes.integrity,'PASS');
  for(const [name,item] of [['original',pack],['tamper-copy',copy]]) {
    const response=await fetch(`${base}/api/exports/${item.id}/pack.zip?run_id=${runId}`,{headers:{cookie:inspector}});assert(response.ok,`ZIP ${response.status}`);
    await fs.writeFile(path.join(out,`${name}.zip`),Buffer.from(await response.arrayBuffer()));
  }
  trace.case=await current('case',caseRecord.id);trace.finding=finding;trace.original_report=originalRecheck;trace.copy_report=copyReport;
  trace.status='PASS';console.log('PASS: real capture → fuel lab → human finding → signed ZIP → tampered copy FAIL → original PASS.');
} catch(error) {trace.status='FAIL';trace.error=error.message;console.error(error.message);process.exitCode=1;}
finally {await fs.writeFile(path.join(out,'http-review.json'),JSON.stringify(trace,null,2));}
