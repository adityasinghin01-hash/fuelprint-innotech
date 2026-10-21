import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { Explain } from '@fp/shared';
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUpRight, Pause, Play } from './icons';
import { captureStage, chapterAt, chapters, openingDuration } from './cinematic/cameraRig';
import './story.css';

const StoryScene = lazy(() => import('./StoryScene'));

export function StoryLanding() {
  const root = useRef<HTMLElement>(null), progress = useRef({ value: 0 });
  const [active,setActive] = useState(0), [ready,setReady] = useState(false), [unavailable,setUnavailable] = useState(false);
  const [paused,setPaused] = useState(false), [animated,setAnimated] = useState(false), [reduced,setReduced] = useState(false);
  const [stage,setStage] = useState('Purge');
  useEffect(() => {
    if (typeof window.matchMedia!=='function' || unavailable) return;
    gsap.registerPlugin(ScrollTrigger);
    const media = gsap.matchMedia();
    media.add('(prefers-reduced-motion: reduce)',() => { setReduced(true); return () => setReduced(false); });
    media.add('(prefers-reduced-motion: no-preference) and (min-height: 620px)',() => {
      const element = root.current!; setAnimated(true);
      const hero = element.querySelector('.opening-title');
      const timeline = gsap.timeline({ scrollTrigger: { trigger: element,start: 'top top',end: 'bottom bottom',scrub: .6,invalidateOnRefresh: true } });
      timeline.to(progress.current,{ value: 1,duration: 1,ease: 'none',onUpdate: () => {
        const value = progress.current.value;setActive(chapterAt(value));setStage(captureStage(value));element.style.setProperty('--cinematic-progress',String(value));
      } },0);
      timeline.to(hero,{autoAlpha:0,y:-65,duration:.08*openingDuration,ease:'power1.inOut'},.06*openingDuration);
      element.querySelectorAll('.opening-caption').forEach((caption,i)=>{
        const start=chapters[i+1].start,next=chapters[i+2]?.start;
        const finale=i===chapters.length-2;
        timeline.fromTo(caption,{autoAlpha:0,y:finale?22:16},{autoAlpha:1,y:0,duration:finale?.02:.012,ease:'power2.out'},start);
        if(next)timeline.to(caption,{autoAlpha:0,y:-12,duration:.008},next-.012);
      });
      timeline.to(element,{ '--reading-scrim':.7,duration:.15*openingDuration,ease:'none' },.11*openingDuration);
      return () => { progress.current.value=0;setAnimated(false);setActive(0); };
    });
    return () => media.revert();
  },[unavailable]);
  useEffect(() => { if(animated)ScrollTrigger.refresh();else setReady(false); },[animated,ready]);
  function jump(index: number) {
    const element = root.current;if(!element)return;
    if(!animated){setActive(index);element.querySelectorAll('.opening-beat')[index]?.scrollIntoView({behavior:'instant'});return;}
    // ScrollTrigger already smooths the camera; a second native smooth scroll can be interrupted by resize.
    window.scrollTo({top:window.scrollY+element.getBoundingClientRect().top+(element.offsetHeight-window.innerHeight)*chapters[index].stop,behavior:'instant'});
  }
  return <section ref={root} className={`scene cinematic-sequence${animated?' is-live':''}${ready?' assets-ready':''}`} data-scene="fuelprint-opening" aria-label="FuelPrint: an illustrated industrial estate">
    <div className="cinematic-stage scene-stage">
      <div className="cinematic-visual" aria-hidden="true">
        <picture className="cinematic-poster"><source media="(max-width:799px)" srcSet={`${import.meta.env.BASE_URL}cinematic/stylized/opening-mobile.webp`} /><img src={`${import.meta.env.BASE_URL}cinematic/stylized/opening.webp`} alt="" /></picture>
        {animated&&!reduced&&!unavailable&&<Suspense fallback={null}><StoryScene progress={progress} paused={paused} onReady={()=>setReady(true)} onUnavailable={()=>{setUnavailable(true);setReady(false);}} /></Suspense>}
      </div>
      <div className="opening-reading-scrim" aria-hidden="true" />
      <div className="opening-title opening-beat" aria-hidden={animated?active!==0:undefined}>
        <h1>FUELPRINT</h1><p>A changing atmosphere.<br />A reason to look closer.</p>
      </div>
      <article className="opening-beat opening-caption opening-beat-two" aria-hidden={animated?active!==1:undefined}>
        <h2>A living estate.<br />A changing sky.</h2>
        <p>Factories. Rooftop sensors. One connected place to begin asking questions.</p>
      </article>
      <article className="opening-beat opening-caption opening-beat-three" aria-hidden={animated?active!==2:undefined}>
        <h2>Small sensors.<br />A wider view.</h2>
        <p>Rooftop monitoring puts each observation in context. A network helps us notice change across the estate.</p>
        <span className="opening-scope opening-limit">Illustrative network · Not a map of live instruments</span>
      </article>
      <article className="opening-beat opening-caption" aria-hidden={animated?active!==3:undefined}>
        <h2>A change.<br />A closer look.</h2>
        <p>A rooftop sensor illustrates a sustained signal change. It prompts investigation, not a conclusion.</p>
        <aside className="story-context" data-ui aria-label="Illustrative monitoring pattern">
          <strong>A change that persists</strong>
          <svg viewBox="0 0 260 44" role="img" aria-label="Conceptual trace: a steady pattern followed by a sustained change. No measured values or time scale."><path d="M2 36 L20 35 L38 37 L56 34 L74 36 L92 35 L110 34 L128 25 L146 15 L164 13 L182 15 L200 12 L218 13 L238 12 L258 13" fill="none" stroke="currentColor" strokeWidth="2"/></svg>
          <Explain what="An illustrative pattern, not live telemetry. No measured values, thresholds or elapsed time are shown." />
        </aside>
      </article>
      <article className="opening-beat opening-caption" aria-hidden={animated?active!==4:undefined}>
        <h2>A signal arrives.<br />Questions open.</h2>
        <p>The signal reaches HQ. In the FuelPrint workflow, an investigation opens while possible explanations remain uncertain.</p>
        <aside className="story-context" data-ui aria-label="Illustrative next measurement">
          <strong>Proposed next measurement</strong><p>Air screening at a planned observation point. A supervisor must review the mission.</p>
          <Explain what="Process illustration only. No investigation, proposal or mission record is created by this page." />
        </aside>
      </article>
      <article className="opening-beat opening-caption" aria-hidden={animated?active!==5:undefined}>
        <h2>People decide.<br />Then we move.</h2>
        <p>A human supervisor reviews and approves the mission before the drone can activate.</p>
        <aside className="story-context" data-ui aria-label="Human mission review">
          <strong>Review the proposed flight</strong><p>Measurement point, route and current conditions. Approval belongs to a person.</p>
          <Explain what="Illustrated human approval, not an interactive approval control. No real mission is approved here." />
        </aside>
      </article>
      <article className="opening-beat opening-caption" aria-hidden={animated?active!==6:undefined}>
        <h2>Ready for<br />a closer look.</h2>
        <p>Power on. Rotors turning. The drone rises above HQ, ready for air screening.</p>
        <span className="opening-scope">A human-approved mission comes before departure.</span>
      </article>
      <article className="opening-beat opening-caption" aria-hidden={animated?active!==7:undefined}>
        <h2>Closer to the air.<br />Still asking why.</h2>
        <p>The drone follows the illustrated approved route toward a measurement point. The plume gives context; it cannot prove a source.</p>
      </article>
      <article className="opening-beat opening-caption" aria-hidden={animated?active!==8:undefined}>
        <h2>Air in.<br />Questions open.</h2>
        <p>The drone holds its position. For online gas capture, purge and settling come before collection through the inlet.</p>
        <aside className="story-context capture-context" data-ui aria-label="Illustrative capture procedure">
          <ol aria-label="Capture stages">{['Purge','Settle','Collect'].map((name)=><li key={name} aria-current={animated&&stage===name?'step':undefined}>{name}</li>)}</ol>
          <p>{!animated?'Purge the inlet, allow settling, then collect.':stage==='Purge'?'Clear the inlet before the observation.':stage==='Settle'?'Allow the instrument response to settle.':'Collect; retain each channel’s quality checks.'}</p>
          <Explain what="Procedure illustration, not a running capture or timer. Air screening remains separate from an inspector’s physical specimen." />
        </aside>
      </article>
      <article className="opening-beat opening-caption opening-review" aria-hidden={animated?active!==9:undefined}>
        <h2>Each channel.<br />Every limitation.</h2>
        <p>A human quality reviewer examines the capture channel by channel. Each screening record carries its status and reasons.</p>
        <aside className="story-context" data-ui aria-label="Channel result meanings">
          <dl><div><dt>Valid</dt><dd>Usable within its stated scope.</dd></div><div><dt>Limited</dt><dd>Keep its restrictions visible.</dd></div><div><dt>Rejected</dt><dd>Retain it with the rejection reason.</dd></div></dl>
          <Explain what="These describe possible server result statuses, not results from this illustration. Actual channel records appear in Cases. Legal suitability: not assessed." />
        </aside>
      </article>
      <article className="opening-beat opening-caption" aria-hidden={animated?active!==10:undefined}>
        <h2>Back to HQ.<br />Carry the context.</h2>
        <p>The drone returns, settles over its platform and lands. The screening record keeps the observation and its limitations together.</p>
        <span className="opening-scope opening-limit">Illustrative return · No physical specimen is created</span>
      </article>
      <article className="opening-beat opening-caption opening-finale" aria-hidden={animated?active!==11:undefined}>
        <h2>Follow the<br />evidence.</h2>
        <p>From an air-screening record to a traceable case. The illustration ends; the real Cases workspace begins below.</p>
        <a className="opening-casework" href="#casework" tabIndex={animated&&active!==11?-1:undefined}>Enter Cases workspace <ArrowDown size={21}/></a>
      </article>
      <div className="opening-topline"><span>KIET InnoTech26</span><Link to="/workspace">Skip to casework <ArrowUpRight size={16}/></Link></div>
      <div className="cinematic-bottom">
        <span className="cinematic-scroll"><ArrowDown size={19}/> {active===0?'Scroll to experience':chapters[active].label}</span>
        <nav aria-label="Story chapters" aria-describedby="cinematic-controls-help">
          <button className="story-seek" aria-label="Previous chapter" disabled={active===0} onClick={()=>jump(active-1)}><ArrowLeft size={18}/></button>
          <select aria-label="Story chapter" value={active} onChange={(event)=>jump(Number(event.target.value))}>{chapters.map((chapter,i)=><option key={chapter.label} value={i}>{i+1} / {chapter.label}</option>)}</select>
          <button className="story-seek" aria-label="Next chapter" disabled={active===chapters.length-1} onClick={()=>jump(active+1)}><ArrowRight size={18}/></button>
        </nav>
        {animated&&<button className="cinematic-pause" aria-describedby="cinematic-controls-help" aria-label={paused?'Resume atmosphere animation':'Pause atmosphere animation'} aria-pressed={paused} onClick={()=>setPaused(!paused)}>{paused?<Play size={18}/>:<Pause size={18}/>}</button>}
      </div>
      <div className="cinematic-disclosure"><span>Illustrative process · Synthetic demonstration</span><details><summary>About this world</summary><div className="cinematic-help"><Explain id="cinematic-controls-help" what="Scroll, choose a numbered chapter, or use the previous/next arrows to move through the same estate. Numbers mark story chapters, not measurements. Pause stops clouds, smoke, rotor rotation and inlet airflow; camera and drone travel still follow scrolling. The ending reveals the real Cases app below. Skip to casework opens it directly."/><p>S01–S06 label fictional factories. The sensor, mission approval, flight, sampling and quality review are illustrations, not live readings or commands. Air screening is separate from an inspector specimen. Smoke does not identify a source or fuel, or establish legal proof.</p><a href={`${import.meta.env.BASE_URL}cinematic/ASSET_CREDITS.md`} target="_blank" rel="noreferrer">Original model and asset credits</a></div></details></div>
      {!ready&&(animated||unavailable)&&!reduced&&<span className="cinematic-loading" role="status">{unavailable?'Static view · 3D unavailable':'Preparing your view…'}</span>}
      <div className="cinematic-progress" aria-hidden="true"/>
    </div>
  </section>;
}
