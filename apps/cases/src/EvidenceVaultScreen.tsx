import { useState } from 'react';
import { apiUrl, Explain } from '@fp/shared';
import type { CaseRecord, ExportRecord, VerificationReportRecord } from '@fp/shared';
import { CheckCircle, Circle, LockKey, ShieldCheck } from './icons';
import { useCases } from './CasesContext';
import { CommandFeedback, Status, useCaseCommand } from './CaseScreens';
import { axisDescriptions, shortId, timeLabel, words } from './caseData';

export function EvidenceVaultScreen({record}: {record:CaseRecord}) {
  const {data,session} = useCases();
  const command = useCaseCommand('exporter');
  const [member,setMember] = useState('');
  const [busy,setBusy] = useState(false);
  const [notice,setNotice] = useState('');
  const exports = data.records('export').filter(item=>item.case_id===record.id);
  const pack = exports.find(item=>item.state!=='TAMPER_DEMO_COPY');
  const reports = data.records('verification_report');
  const latest = (id:string) => reports.filter(item=>item.export_id===id).sort((a,b)=>Date.parse(b.created_utc)-Date.parse(a.created_utc))[0];
  const blocked = command.blocked || busy;
  const canDownload = session.session?.signed_in && Boolean(session.session.station_id) && session.can('exporter') && data.snapshot?.mode==='live';
  async function tamper() {
    if (!pack || blocked) return;
    setBusy(true); setNotice('Creating a separate teaching copy…');
    try {
      const receipt = await command.send('tamper_copy',{export_id:pack.id,member_path:member||pack.members[0]?.path},pack.revision);
      if (receipt?.status!=='APPLIED') {setNotice('Copy creation was not confirmed. Review the receipt before retrying.'); return;}
      const copy = receipt.record_refs.find(ref=>ref.type==='export');
      if (!copy) {setNotice('The receipt did not identify a copy. Reload the records before continuing.');return;}
      const check = await command.send('verify_export',{export_id:copy.id},copy.revision);
      if (check?.status!=='APPLIED') {setNotice('The copy is saved; its check was not confirmed. Use its Verify copy button below.');return;}
      const original = await command.send('verify_export',{export_id:pack.id},pack.revision);
      setNotice(original?.status==='APPLIED' ? 'Both checks are recorded below. Compare the copy and original integrity rows.' : 'Copy check saved. Recheck the original using Verify stored bytes.');
    } finally {setBusy(false);}
  }
  function download(item:ExportRecord) {
    return canDownload && ['SEALED','TAMPER_DEMO_COPY'].includes(item.state)
      ? <a className="button secondary" href={apiUrl(`/api/exports/${encodeURIComponent(item.id)}/pack.zip?run_id=${encodeURIComponent(item.run_id??'')}`)}>Download {item.state==='TAMPER_DEMO_COPY'?'tamper copy':'original'} ZIP</a> : null;
  }
  return <section className="evidence-screen"><div className="screen-title"><LockKey size={32} weight="light"/><div><h2>Preserve the record.</h2><p>Exact bytes. Explicit checks. No single “verified” verdict.</p></div></div>
    <Explain what="A matching hash checks byte integrity. A signature checks those bytes against a key. External trust is a separate decision; none establishes measurement truth, trusted time or legal suitability."/>
    <div className="vault-actions"><button className="button primary" disabled={blocked||Boolean(pack)||record.state!=='FINDING'} onClick={()=>void command.send('seal_export',{case_id:record.id,case_revision:record.revision},record.revision)}><LockKey size={18}/>{pack?'Evidence sealed':'Seal evidence pack'}</button><button className="button secondary" disabled={blocked||!pack} onClick={()=>{if(pack)void command.send('verify_export',{export_id:pack.id},pack.revision);}}><ShieldCheck size={18}/>Verify stored bytes</button>{pack&&download(pack)}</div>
    {command.reason&&<p className="permission-note">{command.reason}</p>}<CommandFeedback receipt={command.receipt}/>
    {pack ? <><div className="manifest-heading"><h3>Manifest {shortId(pack.id)}</h3><p>{pack.created_by==='automation'?'Sealed automatically':'Sealed'} · {timeLabel(pack.created_utc)}</p><code className="hash-text">{pack.manifest_sha256}</code></div>
    <div className="manifest-table">{pack.members.map(item=><div key={item.path}><span>{item.path}<small>SHA-256 {item.sha256.slice(0,12)}…</small></span><strong>{item.length.toLocaleString('en-IN')} B</strong></div>)}</div><Explain what="Each saved member path, byte length and first 12 SHA-256 characters. Downloads contain the stored bytes, not re-created records."/>
    <section className="signature-details"><h3>Signature and external trust.</h3><p>Signing policy: {words(pack.signing_policy??'UNSIGNED')}</p><p>Algorithm: {typeof pack.signature==='object'&&pack.signature ? pack.signature.algorithm : pack.signature===null?'Not signed':'Legacy signature metadata'}</p>{pack.signature_reason&&<p>{words(pack.signature_reason)}</p>}{typeof pack.signature==='object'&&pack.signature&&<><p>Signing key · {pack.signature.key_fingerprint_sha256.slice(0,12)}…</p><code className="hash-text">{pack.signature.key_fingerprint_sha256}</code></>}<Explain what="This is the public key’s SHA-256 fingerprint, not private key material. An included public key is not automatically trusted. Trust anchors must be supplied independently by the owner."/></section>
    <Verification report={latest(pack.id)}/>
    <div className="tamper-demo"><h3>Try changing one byte.</h3><p>Make a separate teaching copy, change one member and check both packs. The original is preserved.</p><label className="form-field">Member to change<select aria-label="Member to change" value={member} onChange={e=>setMember(e.target.value)}>{pack.members.map(item=><option key={item.path} value={item.path}>{item.path}</option>)}</select></label><button className="button secondary" disabled={blocked||!pack.members.length} onClick={()=>void tamper()}>Create and check tamper copy</button><p role="status">{notice}</p>
    {exports.filter(item=>item.state==='TAMPER_DEMO_COPY').map(copy=><article className="tamper-result" key={copy.id}><h4>Teaching tamper copy {shortId(copy.id)}</h4><p>This deliberately altered copy is not evidence.</p><div className="vault-actions"><button className="button secondary" disabled={blocked} onClick={()=>void command.send('verify_export',{export_id:copy.id},copy.revision)}>Verify copy {shortId(copy.id)}</button>{download(copy)}</div><Verification report={latest(copy.id)}/></article>)}</div>
    <details className="offline-help"><summary>Check the ZIP on another laptop</summary><p>Copy the downloaded ZIP and packaged server JAR to the other laptop. The verifier runs without a server, database or network.</p><pre>java -cp fuelprint-server-0.1.0-SNAPSHOT.jar -Dloader.main=fuelprint.cases.EvidenceVerifyCli org.springframework.boot.loader.launch.PropertiesLauncher --pack evidence.zip</pre><p>For a signed pack, add --trust-sha256 followed by a fingerprint obtained independently from the owner. Do not take trust from the downloaded pack itself. For an intentionally unsigned pack, --allow-unsigned explicitly permits an integrity-only result; key trust still says Not signed.</p><Explain what="An exit code is a process result, not a scientific verdict. Keep the verifier’s seven axes and reasons together. Physical second-laptop verification must be demonstrated separately."/></details></>
    : <div className="empty-state"><h3>No sealed pack yet.</h3><p>Record a finding before sealing. The server may seal and verify automatically when configured.</p></div>}
  </section>;
}

function Verification({report}:{report:VerificationReportRecord|undefined}) {
  return <section className="saved-verification"><h3 className="verification-title">Seven checks. Seven meanings.</h3>{report&&<p>{report.created_by==='automation'?'Verified automatically':'Check recorded'} · {timeLabel(report.created_utc)}</p>}
  <div className="verification-axes">{Object.entries(axisDescriptions).map(([key,axis])=><div key={key}>{report?.axes[key as keyof typeof report.axes]==='PASS'?<CheckCircle size={20}/>:<Circle size={20}/>}<span><strong>{axis.title}</strong><small>{axis.meaning}</small></span><Status value={report?.axes[key as keyof typeof report.axes]??'NOT_CHECKED'}/></div>)}</div>
  {report?.signature_check&&<div className="signature-details"><p>Signature validity: <Status value={report.signature_check.validity}/></p><p>External key trust: <Status value={report.signature_check.trust}/></p><p>Trust source: {words(report.signature_check.trust_source)}</p><code className="hash-text">{report.signature_check.key_fingerprint_sha256??'Fingerprint unavailable'}</code><p>{report.signature_check.reason}</p></div>}
  {report?.reasons.length ? <ul className="verification-reasons">{report.reasons.map((reason,index)=><li key={index}>{reason}</li>)}</ul>:null}
  </section>;
}
