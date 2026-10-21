import * as THREE from 'three';
import { clampProgress } from './cameraRig';

/** An authored communication diagram in the estate; never readings, connectivity or source inference. */
export function createMonitoringNetwork(scene: THREE.Scene,sensors: number[][]) {
  const group=new THREE.Group();scene.add(group);
  const brass=new THREE.MeshBasicMaterial({color:'#B47E3F',transparent:true,opacity:0,depthWrite:false});
  const quiet=new THREE.LineBasicMaterial({color:'#476E59',transparent:true,opacity:0,depthWrite:false});
  const source=new THREE.Vector3(...sensors[0] as [number,number,number]).add(new THREE.Vector3(0,.95,0));
  const destination=new THREE.Vector3(0,6.4,7.6);
  const route=new THREE.CubicBezierCurve3(source,new THREE.Vector3(-5,15,5.8),new THREE.Vector3(0,14,7.6),destination);
  const line=new THREE.Mesh(new THREE.TubeGeometry(route,48,.035,5,false),brass);group.add(line);
  const dotGeometry=new THREE.SphereGeometry(.17,12,8),pulse=new THREE.Mesh(dotGeometry,brass);group.add(pulse);
  const ringGeometry=new THREE.TorusGeometry(.5,.028,5,32),rings: THREE.Mesh[]=[];
  const lines: THREE.Line[]=[];
  sensors.forEach((sensor,i)=>{
    const location=new THREE.Vector3(...sensor as [number,number,number]).add(new THREE.Vector3(0,.85,0));
    const ring=new THREE.Mesh(ringGeometry,brass);ring.rotation.x=-Math.PI/2;ring.position.copy(location);group.add(ring);rings.push(ring);
    if(i===0)return;
    const middle=location.clone().lerp(source,.5);middle.y+=3;
    const curve=new THREE.QuadraticBezierCurve3(location,middle,source);
    const networkLine=new THREE.Line(new THREE.BufferGeometry().setFromPoints(curve.getPoints(24)),quiet);
    group.add(networkLine);lines.push(networkLine);
  });
  return {
    update(progress: number,signal: number,communication: number) {
      const enter=clampProgress((progress-.18)/.07),exit=1-clampProgress((progress-.425)/.035);
      group.visible=enter*exit>.001;brass.opacity=enter*exit*.75;quiet.opacity=enter*exit*.13;
      line.visible=communication>0;line.geometry.setDrawRange(0,Math.floor(communication*48)*30);
      pulse.visible=communication>0&&communication<1;pulse.position.copy(route.getPoint(communication));
      rings.forEach((ring,i)=>{ring.visible=i===0?signal>0:progress<.35;ring.scale.setScalar(i===0?1+signal*.35:1);});
    },
    dispose(){scene.remove(group);line.geometry.dispose();lines.forEach((line)=>line.geometry.dispose());dotGeometry.dispose();ringGeometry.dispose();brass.dispose();quiet.dispose();},
  };
}
