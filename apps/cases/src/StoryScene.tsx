import { useEffect, useRef, type MutableRefObject } from 'react';
import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { cameraPose, dronePose, storyState } from './cinematic/cameraRig';
import { createAssetLoader } from './cinematic/sceneAssets';
import { createAtmosphere } from './cinematic/stylizedAtmosphere';
import { createSamplingFlow } from './cinematic/samplingFlow';
import direction from './cinematic/artDirection.json';
import { refineSceneMaterials } from './cinematic/sceneMaterials';
import { createMonitoringNetwork } from './cinematic/monitoringNetwork';

interface Props { progress: MutableRefObject<{ value: number }>; paused: boolean; onReady: () => void; onUnavailable: () => void }
/** The complete journey is an illustration, never a live mission or measurement. */
export default function StoryScene({ progress,paused,onReady,onUnavailable }: Props) {
  const host=useRef<HTMLDivElement>(null),stopped=useRef(paused),ready=useRef(onReady),unavailable=useRef(onUnavailable);
  stopped.current=paused;ready.current=onReady;unavailable.current=onUnavailable;
  useEffect(() => {
    const node=host.current;if (!node) return;
    let renderer: THREE.WebGLRenderer;
    try { renderer=new THREE.WebGLRenderer({antialias:true,powerPreference:'high-performance'}); }
    catch { unavailable.current();return; }
    const compact=window.innerWidth<800;
    renderer.setPixelRatio(Math.min(devicePixelRatio,compact?1.25:1.5));renderer.outputColorSpace=THREE.SRGBColorSpace;
    renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=direction.exposure;
    renderer.shadowMap.enabled=true;renderer.shadowMap.type=THREE.PCFShadowMap;renderer.shadowMap.autoUpdate=false;
    node.appendChild(renderer.domElement);renderer.domElement.setAttribute('aria-hidden','true');
    const scene=new THREE.Scene(),fog=new THREE.FogExp2(direction.atmosphere,direction.fogDensity);
    const worldColor=new THREE.Color(direction.atmosphere),endingColor=new THREE.Color(direction.canvas);
    scene.background=worldColor;scene.fog=fog;
    const camera=new THREE.PerspectiveCamera(36,1,.3,1100);
    const sun=new THREE.DirectionalLight(direction.sunColor,direction.sunIntensity);sun.position.set(-40,90,45);sun.castShadow=true;
    sun.shadow.mapSize.set(compact?1024:2048,compact?1024:2048);
    Object.assign(sun.shadow.camera,{left:-85,right:85,top:85,bottom:-85,near:1,far:220});
    // The phone's existing 1024 map needs more bias to avoid terrain self-shadow stripes.
    sun.shadow.normalBias=compact?.14:.08;sun.shadow.bias=compact?-.00035:-.0002;sun.shadow.radius=2;
    scene.add(sun,sun.target,new THREE.HemisphereLight(direction.skyLight,direction.groundLight,direction.hemisphereIntensity));
    const pmrem=new THREE.PMREMGenerator(renderer),room=new RoomEnvironment(),environment=pmrem.fromScene(room,.04);
    scene.environment=environment.texture;scene.environmentIntensity=direction.environmentIntensity;room.dispose();pmrem.dispose();
    const assets=createAssetLoader();
    let disposed=false,loaded=false,visible=true,frame=0,mobile=compact;
    let lastTime=0,lastProgress=-1,ambientTime=7,rotorTime=0,sampleStart=0,frames=0,cpu=0;
    let atmosphere: ReturnType<typeof createAtmosphere> | undefined,estate: THREE.Group | undefined;
    let drone: THREE.Group | undefined,sensor: THREE.Group | undefined,flow: ReturnType<typeof createSamplingFlow> | undefined;
    let network: ReturnType<typeof createMonitoringNetwork> | undefined;
    const rotors: THREE.Object3D[]=[];
    let signalLens: THREE.MeshStandardMaterial | undefined,powerLens: THREE.MeshStandardMaterial | undefined;
    const idleSignal=new THREE.Color('#6F9277'),activeSignal=new THREE.Color(direction.amber);
    const idlePower=new THREE.Color(direction.forest),activePower=new THREE.Color('#D2DDBD');
    const releaseModel=(root: THREE.Object3D) => {
      const geometries=new Set<THREE.BufferGeometry>(),materials=new Set<THREE.Material>();
      root.traverse((o) => {if(o instanceof THREE.Mesh){geometries.add(o.geometry);(Array.isArray(o.material)?o.material:[o.material]).forEach((m) => materials.add(m));}});
      geometries.forEach((g) => g.dispose());materials.forEach((m) => m.dispose());
    };
    Promise.all([assets.model('stylized/estate'),assets.openingLayout(),assets.model('stylized/drone'),assets.model('stylized/sensor')]).then(([model,layout,aircraft,instrument]) => {
      if(disposed){[model,aircraft,instrument].forEach((g)=>releaseModel(g.scene));return;}
      estate=model.scene;estate.traverse((o) => {if(o instanceof THREE.Mesh){o.castShadow=true;o.receiveShadow=true;}});
      drone=aircraft.scene;drone.position.set(5.6,11.82,-1.12);
      flow=createSamplingFlow(drone);
      sensor=instrument.scene;sensor.position.set(...layout.sensors[0] as [number,number,number]);
      [estate,drone,sensor].forEach(refineSceneMaterials);
      for(const group of [drone,sensor])group.traverse((o)=>{
        if(o.name.startsWith('Rotor_'))rotors.push(o);
        if(o instanceof THREE.Mesh){
          o.castShadow=true;o.receiveShadow=true;
          const materials=Array.isArray(o.material)?o.material:[o.material];
          for(const material of materials)if(material instanceof THREE.MeshStandardMaterial){
            if(material.name==='SignalLens')signalLens=material;
            if(material.name==='PowerLens')powerLens=material;
          }
        }
      });
      scene.add(estate,drone,sensor);scene.updateMatrixWorld(true);atmosphere=createAtmosphere(scene,layout.emitters,compact);
      network=createMonitoringNetwork(scene,layout.sensors);
      renderer.shadowMap.needsUpdate=true;loaded=true;ready.current();
    }).catch((error: unknown) => {if(!disposed){console.error('The illustrated estate could not load.',error);unavailable.current();}});
    const resize=() => {
      const {width,height}=node.getBoundingClientRect();mobile=width<800;renderer.setSize(width,height);
      camera.aspect=width/Math.max(1,height);camera.updateProjectionMatrix();lastProgress=-1;
    };
    const resizeObserver=new ResizeObserver(resize);resizeObserver.observe(node);resize();
    const draw=(time: number) => {
      if(disposed||!visible)return;frame=requestAnimationFrame(draw);
      if(!loaded||document.hidden){lastTime=time;return;}
      const moving=Math.abs(progress.current.value-lastProgress)>.00001;
      if(time-lastTime<(moving?15:32))return;
      const delta=Math.min(.05,(time-lastTime)/1000);lastTime=time;
      if(!moving&&stopped.current&&lastProgress>=0)return;
      const start=performance.now(),pose=cameraPose(progress.current.value,mobile);
      camera.position.set(...pose.position);camera.lookAt(...pose.target);
      if(camera.fov!==pose.fov){camera.fov=pose.fov;camera.updateProjectionMatrix();}
      if(!stopped.current)ambientTime+=delta;
      const state=storyState(progress.current.value);
      // Reset before preparation only; after shutdown, hold the final blade angle instead of snapping.
      if(progress.current.value<.48)rotorTime=0;else if(!stopped.current)rotorTime+=delta*state.power;
      if(drone){
        const flight=dronePose(progress.current.value);
        drone.position.set(...flight.position);drone.rotation.set(0,flight.yaw,flight.bank);
        rotors.forEach((rotor,i)=>{rotor.rotation.y=rotorTime*42*(i%2?1:-1);});
        if(moving)renderer.shadowMap.needsUpdate=true;
      }
      if(signalLens){signalLens.color.lerpColors(idleSignal,activeSignal,state.signal);signalLens.emissive.set('#d6a958');signalLens.emissiveIntensity=state.signal*.35;}
      if(powerLens){powerLens.color.lerpColors(idlePower,activePower,state.power);powerLens.emissive.set('#d6dbb1');powerLens.emissiveIntensity=state.power*.65;}
      flow?.update(ambientTime,state.collection);
      network?.update(progress.current.value,state.signal,state.communication);
      // Keep HQ and the landed aircraft visible through the handoff; no full-screen white-out.
      fog.density=direction.fogDensity+state.reveal*.0005;fog.color.lerpColors(worldColor,endingColor,state.reveal*.2);
      atmosphere?.update(ambientTime,camera);renderer.render(scene,camera);lastProgress=progress.current.value;
      frames++;cpu+=performance.now()-start;
      if(time-sampleStart>2000){
        node.dataset.frameRate=(frames*1000/Math.max(1,time-sampleStart)).toFixed(1);node.dataset.renderCpuMs=(cpu/frames).toFixed(1);
        node.dataset.drawCalls=String(renderer.info.render.calls);node.dataset.triangles=String(renderer.info.render.triangles);sampleStart=time;frames=0;cpu=0;
      }
      node.dataset.cameraPosition=pose.position.map((n)=>n.toFixed(3)).join(',');node.dataset.scrollProgress=lastProgress.toFixed(4);node.dataset.ambientTime=ambientTime.toFixed(3);
      node.dataset.illustrationState=JSON.stringify(state);
      node.dataset.rotorPhase=rotorTime.toFixed(3);
      node.dataset.dronePosition=drone?.position.toArray().map((n)=>n.toFixed(3)).join(',');
    };
    const observer=new IntersectionObserver(([entry])=>{visible=entry.isIntersecting;cancelAnimationFrame(frame);if(visible)frame=requestAnimationFrame(draw);});observer.observe(node);
    const lost=(event: Event)=>{event.preventDefault();unavailable.current();};renderer.domElement.addEventListener('webglcontextlost',lost);
    return ()=>{
      disposed=true;cancelAnimationFrame(frame);resizeObserver.disconnect();observer.disconnect();assets.dispose();atmosphere?.dispose();flow?.dispose();network?.dispose();
      [estate,drone,sensor].forEach((model)=>{if(model)releaseModel(model);});environment.dispose();renderer.dispose();renderer.domElement.remove();
    };
  },[progress]);
  return <div className="cinematic-renderer" ref={host} />;
}
