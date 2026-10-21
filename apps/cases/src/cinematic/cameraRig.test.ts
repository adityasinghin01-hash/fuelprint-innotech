import { describe, expect, it } from 'vitest';
import { cameraPose, captureStage, chapters, clampProgress, dronePose, journeyKeys, openingDuration, storyState } from './cameraRig';

describe('cinematic camera', () => {
  it('returns the same spatial state after traversing forward then backward', () => {
    for (const mobile of [false, true]) {
      const forward = Array.from({ length: 101 }, (_, i) => cameraPose(i / 100, mobile));
      for (let i = 100; i >= 0; i--) expect(cameraPose(i / 100, mobile)).toEqual(forward[i]);
    }
  });
  it('keeps adjacent frames continuous around every waypoint', () => {
    const waypoints=[.03,.15,.335,.49,.5,.53,.65,.7,.8,.85,.99].map((p)=>p*openingDuration).concat(journeyKeys.slice(0,-1),chapters.map((chapter)=>chapter.start));
    for (const mobile of [false, true]) for (const p of waypoints) {
      const a = cameraPose(p - .0001, mobile), b = cameraPose(p + .0001, mobile);
      // A frame must not jump at a chapter, spline knot, or the return handoff.
      expect(Math.hypot(...a.position.map((n, i) => n - b.position[i]))).toBeLessThan(.3/openingDuration);
      // The flight look target tracks the faster aircraft; still reject a discontinuous jump.
      expect(Math.hypot(...a.target.map((n, i) => n - b.target[i]))).toBeLessThan(p>openingDuration?.2:.04/openingDuration);
    }
  });
  it('handles overscroll and invalid progress without an invalid camera', () => {
    expect(cameraPose(-1)).toEqual(cameraPose(0));
    expect(cameraPose(2)).toEqual(cameraPose(1));
    expect(clampProgress(Number.NaN)).toBe(0);
  });
  it('keeps both camera routes above the estate and clear of the buildings', () => {
    for (const mobile of [false,true]) for (let i=0;i<=100;i++) {
      const pose=cameraPose(i/100,mobile);
      // The close sensor orbit stays above HQ's 12-unit roof/mast envelope.
      expect(pose.position[1]).toBeGreaterThanOrEqual(14);
      expect(Math.hypot(...pose.position.map((n,j)=>n-pose.target[j]))).toBeGreaterThan(10);
      expect(pose.position.every(Number.isFinite)).toBe(true);
    }
  });
  it('holds the drone on its dock until the human approval beat is complete',()=>{
    for(let i=0;i<=1000;i++){
      const state=storyState(i/1000);
      if(state.approval<1){expect(state.power).toBe(0);expect(state.lift).toBe(0);}
      if(state.power<1)expect(state.lift).toBe(0);
    }
    expect(storyState(.65).signal).toBe(1);
    expect(storyState(1).lift).toBe(0);
  });
  it('restores every illustrated state when scrolling back, including docking and lights',()=>{
    const forward=Array.from({length:101},(_,i)=>storyState(i/100));
    for(let i=100;i>=0;i--)expect(storyState(i/100)).toEqual(forward[i]);
    expect(Object.values(storyState(0)).every((value)=>value===0)).toBe(true);
  });
  it('orders departure, collection, human review and reveal without skipping prerequisites',()=>{
    for(let i=0;i<=1000;i++) {
      const state=storyState(i/1000);
      if(state.travel>0&&state.returning===0){expect(state.approval).toBe(1);expect(state.power).toBe(1);expect(state.lift).toBe(2.8);}
      if(state.collection>0){expect(state.travel).toBe(1);expect(state.purge).toBe(1);expect(state.settle).toBe(1);}
      if(state.review>0)expect(state.sample).toBe(1);
      if(state.returning>0){expect(state.review).toBe(1);expect(state.collection).toBe(0);}
      if(state.landing>0)expect(state.returning).toBe(1);
      if(state.reveal>0){expect(state.review).toBe(1);expect(state.landing).toBe(1);expect(state.collection).toBe(0);}
    }
  });
  it('returns the drone through the same flight path and holds above the estate roofs',()=>{
    const forward=Array.from({length:1001},(_,i)=>dronePose(i/1000));
    for(let i=1000;i>=0;i--) {
      const pose=dronePose(i/1000);
      expect(pose).toEqual(forward[i]);
      if(storyState(i/1000).travel>0&&i<907)expect(pose.position[1]).toBeGreaterThanOrEqual(14.62);
      // The shorter return is faster, but must still be continuous at every 0.1% scroll sample.
      if(i>0)expect(Math.hypot(...pose.position.map((n,j)=>n-forward[i-1].position[j]))).toBeLessThan(1.3);
    }
    expect(dronePose(0).position).toEqual([5.6,11.82,-1.12]);
    expect(dronePose(1).position).toEqual(dronePose(0).position);
    expect(storyState(1).power).toBe(0);
  });
  it('keeps the aircraft horizontally framed throughout portrait flight, capture and return',()=>{
    for(const width of [320,390])for(let i=540;i<=940;i++) {
      const p=i/1000,pose=cameraPose(p,true),aircraft=dronePose(p).position;
      const direction=pose.target.map((n,j)=>n-pose.position[j]);
      const length=Math.hypot(...direction),forward=direction.map((n)=>n/length);
      const right=[-forward[2],0,forward[0]],rightLength=Math.hypot(...right);
      const offset=aircraft.map((n,j)=>n-pose.position[j]);
      const depth=offset.reduce((sum,n,j)=>sum+n*forward[j],0);
      const horizontal=offset.reduce((sum,n,j)=>sum+n*right[j]/rightLength,0);
      const halfWidth=depth*Math.tan(pose.fov*Math.PI/360)*(width/844);
      // Includes a conservative 2.8-unit rotor-guard radius, not just the aircraft centre.
      expect(depth).toBeGreaterThan(0);
      expect(Math.abs(horizontal)+2.8).toBeLessThan(halfWidth);
    }
  });
  it('gives communication and human review time before takeoff, and returns before rotor shutdown',()=>{
    expect(storyState(.427).communication).toBe(1);
    expect(storyState(.48).approval).toBe(1);
    expect(storyState(.51).power).toBe(1);
    expect(storyState(.942).landing).toBe(1);
    expect(storyState(.942).power).toBe(1);
    expect(storyState(.968).power).toBe(0);
    expect(captureStage(.7)).toBe('Purge');expect(captureStage(.725)).toBe('Settle');
    expect(captureStage(.755)).toBe('Collect');expect(captureStage(.81)).toBe('Review');
    // Distinct forward return, rather than requiring the visitor to rewind the outbound shot.
    expect(dronePose(.87).position).not.toEqual(dronePose(.61).position);
    expect(dronePose(.941).position).toEqual([5.6,11.82,-1.12]);
    expect(.905-.837).toBeLessThan(.68-.55);
  });
  it('turns into the return direction without an angular jump and retains HQ at the ending',()=>{
    for(let i=1;i<=1000;i++)expect(Math.abs(dronePose(i/1000).yaw-dronePose((i-1)/1000).yaw)).toBeLessThan(.35);
    // The opening return tangent moves east/north on screen, not the former arbitrary half-turn.
    expect(dronePose(.837).yaw).toBeGreaterThan(0);
    expect(dronePose(.837).yaw).toBeLessThan(Math.PI/2);
    expect(cameraPose(1).target[1]).toBe(12);
    expect(dronePose(1).position).toEqual([5.6,11.82,-1.12]);
  });
  it('keeps the whole aircraft inside desktop framing during outbound travel and return',()=>{
    for(const width of [1024,1440])for(let i=540;i<=940;i++){
      const p=i/1000,pose=cameraPose(p),aircraft=dronePose(p).position;
      const vector=pose.target.map((n,j)=>n-pose.position[j]),length=Math.hypot(...vector),forward=vector.map((n)=>n/length);
      const right=[-forward[2],0,forward[0]],rightLength=Math.hypot(...right),offset=aircraft.map((n,j)=>n-pose.position[j]);
      const depth=offset.reduce((sum,n,j)=>sum+n*forward[j],0),horizontal=offset.reduce((sum,n,j)=>sum+n*right[j]/rightLength,0);
      const halfWidth=depth*Math.tan(pose.fov*Math.PI/360)*(width/900);
      expect(Math.abs(horizontal)+2.8).toBeLessThan(halfWidth);
    }
  });
});
