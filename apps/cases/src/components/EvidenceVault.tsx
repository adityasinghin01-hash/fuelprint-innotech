import { apiUrl, Button, Explain } from '@fp/shared';
import type { CaseRecord } from '@fp/shared';
import { Feedback, useCases, useCasesCommand } from './CasesContext';
export function EvidenceVault({record}:{record:CaseRecord}){
  const {snapshot,session}=useCases();const action=useCasesCommand('exporter');
  const exports=snapshot.records('export').filter(e=>e.case_id===record.id);
  return <section className="case-panel"><h2>6 · Evidence vault</h2>
    <Button disabled={action.disabled || record.state!=='FINDING'} onClick={()=>void action.send('seal_export',{case_id:record.id,case_revision:record.revision},record.revision)}>Seal current finding</Button>
    <Explain what="Seal the current exact case revision. Hashes verify stored bytes; they do not establish physical truth, trusted time or legal suitability."/>
    {exports.map(e=><article key={e.id}><h3>{e.id}</h3><p>{e.state} · case revision {e.case_revision}</p>
      <p>Manifest SHA256: <code>{e.manifest_sha256}</code></p>
      <p>Signature: {e.signature===null?'NOT_SIGNED':typeof e.signature==='string'?e.signature:e.signature.algorithm}</p>
      {e.signature_reason?<p>{e.signature_reason}</p>:null}
      {e.signature && typeof e.signature==='object'?<p>Signing key fingerprint SHA256: <code>{e.signature.key_fingerprint_sha256}</code></p>:null}
      <Explain what="A signature and a trusted key are separate checks. The included public key is not automatically trusted. Neither check establishes a person's identity, trusted time, measurement truth or legal suitability."/>
      {!action.disabled && session.session?.station_id && ['SEALED','TAMPER_DEMO_COPY'].includes(e.state)?<p><a href={apiUrl(`/api/exports/${encodeURIComponent(e.id)}/pack.zip?run_id=${encodeURIComponent(e.run_id ?? '')}`)}>Download ZIP {e.id}</a></p>:null}
      <Button disabled={action.disabled} onClick={()=>void action.send('verify_export',{export_id:e.id},e.revision)}>Verify export {e.id}</Button>
      <Button variant="secondary" disabled={action.disabled || e.state!=='SEALED' || !e.members.length}
        onClick={()=>void action.send('tamper_copy',{export_id:e.id,member_path:e.members[0].path},e.revision)}>Make tampered COPY of {e.id}</Button>
      <Explain what="The tamper command changes one member in a separate teaching copy. The original bytes remain preserved."/>
      <details><summary>Pack members</summary><ul>{e.members.map(m=><li key={m.path}>{m.path} · {m.length} bytes · {m.sha256}</li>)}</ul></details>
      {snapshot.records('verification_report').filter(v=>v.export_id===e.id).map(v=><section key={v.id} aria-label={`Verification ${e.id}`}><h4>Saved verification axes</h4>
        <dl>{Object.entries(v.axes).map(([axis,value])=><div key={axis}><dt>{axis}</dt><dd>{value}</dd></div>)}</dl><ul>{v.reasons.map((reason,i)=><li key={i}>{reason}</li>)}</ul>
        {v.signature_check?<div><p>Signature validity: {v.signature_check.validity}</p><p>External key trust: {v.signature_check.trust}</p>
          <p>Trust source: {v.signature_check.trust_source}</p><p>Key fingerprint SHA256: <code>{v.signature_check.key_fingerprint_sha256 ?? 'unknown'}</code></p><p>{v.signature_check.reason}</p></div>:null}
      </section>)}
    </article>)}
    <Explain what="Download the saved original or clearly labelled tamper copy for offline verification. The offline verifier checks those saved bytes with separately supplied trusted fingerprints. Aerosol filters remain separate from fuel specimens."/>
    <Feedback action={action}/>
  </section>;
}
