export type Point = readonly [number, number, number];
export interface CameraPose { position: Point; target: Point; fov: number }
const desktop: Point[] = [[100,105,130],[83,81,106],[61,52,77],[41,32,55],[26,20,35]];
const phone: Point[] = [[107,135,165],[84,99,135],[55,62,95],[34,38,67],[25,26,49]];
const desktopTargets: Point[] = [[0,20,-10],[-3,13,-8],[-7,8,-5],[-8,6,-2],[-7,5,0]];
const phoneTargets: Point[] = [[0,24,-9],[0,20,-6],[-2,16,-3],[-1,14,0],[0,12,0]];
export function clampProgress(value: number) { return Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0; }
const distance = (a: Point, b: Point) => Math.hypot(...a.map((n, i) => n - b[i]));
const mix = (a: Point, b: Point, t: number): Point => a.map((n, i) => n + (b[i] - n) * t) as unknown as Point;
/** Centripetal Catmull–Rom avoids loops around the close HQ approach. */
function spline(points: Point[], value: number): Point {
  const scaled = clampProgress(value) * (points.length - 1), i = Math.min(points.length - 2, Math.floor(scaled));
  const p1 = points[i], p2 = points[i + 1];
  const p0 = points[i - 1] ?? mix(p2,p1,2), p3 = points[i + 2] ?? mix(p1,p2,2);
  const t1 = Math.sqrt(distance(p0,p1)), t2 = t1 + Math.sqrt(distance(p1,p2)), t3 = t2 + Math.sqrt(distance(p2,p3));
  const t = t1 + (scaled - i) * (t2 - t1);
  const a1 = mix(p0,p1,t / t1), a2 = mix(p1,p2,(t - t1) / (t2 - t1)), a3 = mix(p2,p3,(t - t2) / (t3 - t2));
  const b1 = mix(a1,a2,t / t2), b2 = mix(a2,a3,(t - t1) / (t3 - t1));
  return mix(b1,b2,(t - t1) / (t2 - t1));
}
function path(points: Point[]) {
  const samples = Array.from({ length: 201 }, (_, i) => spline(points,i / 200));
  const lengths = [0]; samples.slice(1).forEach((p,i) => lengths.push(lengths[i] + distance(samples[i],p)));
  return (value: number) => {
    const length = clampProgress(value) * lengths[200];
    const end = Math.max(1,lengths.findIndex((n) => n >= length));
    return spline(points,(end - 1 + (length - lengths[end - 1]) / (lengths[end] - lengths[end - 1])) / 200);
  };
}
const routes = [path(desktop),path(phone)];
function openingPose(value: number, mobile = false): CameraPose {
  const p = clampProgress((clampProgress(value) - .06) / .92);
  const t = p * p * p * (p * (p * 6 - 15) + 10);
  return { position: routes[mobile ? 1 : 0](t), target: spline(mobile ? phoneTargets : desktopTargets,t), fov: mobile ? 42 : 36 };
}
const smooth = (value: number) => { const t=clampProgress(value);return t*t*t*(t*(t*6-15)+10); };
const continuation: CameraPose[][] = [
  [openingPose(1),{position:[0,14,13],target:[-9,11.3,4],fov:36},{position:[20,19,23],target:[-2,11,0],fov:36},{position:[17,17,18],target:[0,13,-1],fov:36}],
  [openingPose(1,true),{position:[-.5,18,19],target:[-6,13,4],fov:42},{position:[32,32,47],target:[0,16,0],fov:42},{position:[20,23,25],target:[5.6,19,-1.12],fov:42}],
];
/** A single world; each deliberate camera hold gives the next story beat room to read. */
function estateCameraPose(value: number,mobile=false): CameraPose {
  const p=clampProgress(value);
  if(p<=.5)return openingPose(p/.5,mobile);
  const keys=[.5,.65,.8,1],poses=continuation[mobile?1:0];
  const i=p<.65?0:p<.8?1:2,t=smooth((p-keys[i])/(keys[i+1]-keys[i]));
  return {position:mix(poses[i].position,poses[i+1].position,t),target:mix(poses[i].target,poses[i+1].target,t),fov:poses[i].fov};
}
export const openingDuration=.54;
export const chapters=[
  {label:'The estate',start:0,stop:0},
  {label:'A connected world',start:.07,stop:.12},
  {label:'Rooftop monitoring',start:.18,stop:.265},
  {label:'Signal change',start:.285,stop:.335},
  {label:'Signal to headquarters',start:.355,stop:.407},
  {label:'Human approval',start:.425,stop:.465},
  {label:'Lift-off',start:.48,stop:.536},
  {label:'Outbound flight',start:.555,stop:.645},
  {label:'Air screening',start:.69,stop:.756},
  {label:'Channel review',start:.79,stop:.82},
  {label:'Return to headquarters',start:.837,stop:.924},
  {label:'Follow the evidence',start:.955,stop:.995},
];
export function chapterAt(value: number) {
  const p=clampProgress(value);
  return Math.max(0,chapters.findIndex((chapter,i)=>p>=chapter.start&&(i===chapters.length-1||p<chapters[i+1].start)));
}
export const journeyKeys=[.54,.645,.735,.82,.905,.94,1];
const journeyCameras: CameraPose[][]=[
  [estateCameraPose(1),{position:[43,26,10],target:[18,17,-9],fov:36},{position:[42,22,-5],target:[26,18,-21],fov:36},{position:[46,28,-1],target:[26,21,-23],fov:36},{position:[27,25,21],target:[1,15,-1],fov:36},{position:[22,20,26],target:[0,12,0],fov:36},{position:[37,28,42],target:[-7,12,0],fov:36}],
  [estateCameraPose(1,true),{position:[48,32,17],target:[20,25,-10],fov:42},{position:[45,25,-1],target:[30.2,22,-21],fov:42},{position:[49,31,1],target:[30.2,25,-21],fov:42},{position:[28,29,26],target:[5.6,21,-1.12],fov:42},{position:[24,26,30],target:[5.6,20,-1.12],fov:42},{position:[42,38,62],target:[5.6,22,-1.12],fov:42}],
];
/** One camera continues through outbound flight, capture, return, landing and the cloud veil. */
export function cameraPose(value: number,mobile=false): CameraPose {
  const p=clampProgress(value);
  if(p<=openingDuration)return estateCameraPose(p/openingDuration,mobile);
  const next=journeyKeys.findIndex((_,index)=>index<journeyKeys.length-1&&p<journeyKeys[index+1]),i=next<0?journeyKeys.length-2:next;
  const t=smooth((p-journeyKeys[i])/(journeyKeys[i+1]-journeyKeys[i]));
  const poses=journeyCameras[mobile?1:0];
  const position=mix(poses[i].position,poses[i+1].position,t),look= mix(poses[i].target,poses[i+1].target,t);
  // Portrait framing follows the aircraft horizontally throughout the flight, not a point ahead of it.
  const aircraft=dronePose(p).position;
  const dx=aircraft[0]-position[0],dz=aircraft[2]-position[2],length=Math.hypot(dx,dz);
  const framed: Point=[aircraft[0]+dz/length*3.5,look[1],aircraft[2]-dx/length*3.5];
  const tracking=smooth((p-.54)/.035)*(1-smooth((p-.905)/.035));
  const target: Point=mobile?[aircraft[0],look[1],aircraft[2]]:mix(look,framed,tracking);
  return {position,target,fov:poses[i].fov};
}
/** Illustration only. These values never read or update a mission or measurement. */
export function storyState(value: number) {
  const p=clampProgress(value),opening=p/openingDuration;
  const landing=smooth((p-.907)/.033);
  return {
    signal:smooth((opening-.54)/.07),communication:smooth((p-.35)/.062),
    approval:smooth((p-.427)/.038),power:smooth((p-.48)/.023)*(1-smooth((p-.942)/.026)),
    lift:2.8*smooth((p-.51)/.025)*(1-landing),
    travel:smooth((p-.55)/.13),purge:smooth((p-.69)/.018),settle:smooth((p-.714)/.018),sample:smooth((p-.74)/.035),
    collection:smooth((p-.74)/.009)*(1-smooth((p-.775)/.012)),
    review:smooth((p-.793)/.03),returning:smooth((p-.837)/.068),landing,
    reveal:smooth((p-.955)/.04),
  };
}
/** Authored flight spline, not Field route planning or source selection. */
export function dronePose(value: number) {
  const p=clampProgress(value),state=storyState(p),t=state.travel;
  const a: Point=[5.6,11.82+state.lift,-1.12],b: Point=[9,21,-5],c: Point=[24,21,-12],d: Point=[30.2,17.8,-21];
  // Cubic Bézier with smooth progress: zero velocity at dock departure and hover arrival.
  const bezier=(a: Point,b: Point,c: Point,d: Point,t: number)=>mix(mix(mix(a,b,t),mix(b,c,t),t),mix(mix(b,c,t),mix(c,d,t),t),t);
  const heading=(a: Point,b: Point,c: Point,d: Point,t: number)=>{
    const tangent=a.map((_,i)=>3*(1-t)**2*(b[i]-a[i])+6*(1-t)*t*(c[i]-b[i])+3*t*t*(d[i]-c[i]));
    return Math.atan2(tangent[0],tangent[2]);
  };
  if(p>=.823){
    // A separate, shorter return arc clears the roofs, then descends vertically onto the same pad.
    const r=state.returning,arrival: Point=[5.6,14.62,-1.12];
    const airborne=bezier(d,[35,22,-13],[12,21,3],arrival,r);
    const position: Point=state.landing===1?[5.6,11.82,-1.12]:mix(airborne,[5.6,11.82,-1.12],state.landing);
    const turn=smooth((p-.823)/.014),align=smooth((r-.8)/.2);
    return {position,yaw:heading(d,[35,22,-13],[12,21,3],arrival,r)*turn*(1-align),bank:.09*Math.sin(Math.PI*r)};
  }
  const position=bezier(a,b,c,d,t);
  return {position,yaw:heading(a,b,c,d,t)*smooth(t/.12)*(1-smooth((t-.86)/.14)),bank:-.10*Math.sin(Math.PI*t)};
}

/** Narrative procedure labels only, not the server's capture state machine or quality results. */
export function captureStage(value: number) {
  const p=clampProgress(value);
  return p<.714?'Purge':p<.74?'Settle':p<.79?'Collect':'Review';
}
