import { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { CSS2DRenderer } from 'three/addons/renderers/CSS2DRenderer.js';
import type { TickMessage } from '@fp/shared';
import { createEstate } from './estate';

export default function FieldScene({frame}:{frame:TickMessage|null}) {
  const host=useRef<HTMLDivElement>(null);const observed=useRef(frame);observed.current=frame;
  const draw=useRef<(()=>void)|null>(null);const [failure,setFailure]=useState<string|null>(null);
  useEffect(()=>{
    if(failure || !host.current)return;
    if(typeof WebGL2RenderingContext==='undefined'){setFailure('WebGL is unavailable');return;}
    const element=host.current;let renderer:THREE.WebGLRenderer|null=null;
    let estate:ReturnType<typeof createEstate>|null=null;let controls:OrbitControls|null=null;let labels:CSS2DRenderer|null=null;
    let resize:ResizeObserver|null=null;
    const lost=(event:Event)=>{event.preventDefault();setFailure('Graphics context interrupted');};
    function cleanup(){
      draw.current=null;resize?.disconnect();controls?.dispose();estate?.dispose();
      renderer?.domElement.removeEventListener('webglcontextlost',lost);renderer?.dispose();renderer?.domElement.remove();labels?.domElement.remove();
    }
    try {
      renderer=new THREE.WebGLRenderer({antialias:true,powerPreference:'low-power'});
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1,2));renderer.outputColorSpace=THREE.SRGBColorSpace;
      renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1;
      renderer.domElement.setAttribute('aria-hidden','true');renderer.domElement.addEventListener('webglcontextlost',lost);
      element.appendChild(renderer.domElement);estate=createEstate();
      labels=new CSS2DRenderer();Object.assign(labels.domElement.style,{position:'absolute',inset:'0',pointerEvents:'none'});element.appendChild(labels.domElement);
      const camera=new THREE.PerspectiveCamera(48,1,1,4000);camera.position.set(650,700,800);camera.lookAt(0,0,0);
      controls=new OrbitControls(camera,renderer.domElement);controls.enableDamping=false;controls.minDistance=150;controls.maxDistance=1800;controls.maxPolarAngle=Math.PI/2.1;
      const render=()=>{ if(!renderer || !estate || !labels)return;estate.update(observed.current?.payload.vehicles.find(v=>v.kind==='DRONE') ?? null);renderer.render(estate.scene,camera);labels.render(estate.scene,camera); };
      const size=()=>{const width=element.clientWidth || 600,height=element.clientHeight || 500;renderer!.setSize(width,height);labels!.setSize(width,height);camera.aspect=width/height;camera.updateProjectionMatrix();render();};
      draw.current=render;controls.addEventListener('change',render);resize=new ResizeObserver(size);resize.observe(element);size();
    }catch{cleanup();setFailure('3D renderer could not start');return;}
    return cleanup;
  },[failure]);
  useEffect(()=>{draw.current?.();},[frame]);
  return <figure style={{margin:0}}><div className="field-canvas" ref={host} data-testid="field-scene" data-run-id={frame?.run_id ?? ''} data-sim-time={frame?.sim_time_s ?? ''} aria-label="Estate and observed drone position">
    {failure ? <p role="status">{failure}; textual server observations remain available.</p> : null}
  </div><figcaption>Fictional estate · public world profile. Drag to inspect; the blue dot marks the observed inlet. Drone marker enlarged for visibility. No plume or collection physics is rendered.</figcaption></figure>;
}
