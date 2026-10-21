import * as THREE from 'three';
import direction from './artDirection.json';

interface Finish { color: string; roughness: number; metalness: number }
const finishes: Record<string,Finish>=direction.materials;

/** Grade the existing asset slots once at load; preserve authored geometry and draw-call count. */
export function refineSceneMaterials(root: THREE.Object3D) {
  const meadowDark=new THREE.Color(direction.meadowDark),meadowLight=new THREE.Color(direction.meadowLight);
  const painted=new Set<THREE.BufferGeometry>(),color=new THREE.Color();
  root.traverse((object)=>{
    if(!(object instanceof THREE.Mesh))return;
    const materials=Array.isArray(object.material)?object.material:[object.material];
    for(const material of materials) {
      if(!(material instanceof THREE.MeshStandardMaterial))continue;
      const finish=finishes[material.name];
      if(!finish)continue;
      material.color.set(finish.color);material.roughness=finish.roughness;material.metalness=finish.metalness;
      if(material.name!=='Painted meadow'||painted.has(object.geometry))continue;
      const colors=object.geometry.getAttribute('color');
      if(colors)for(let i=0;i<colors.count;i++) {
        // Retain the authored meadow pattern; only change its green range.
        const t=THREE.MathUtils.clamp((colors.getY(i)-.25)/.13,0,1);
        color.lerpColors(meadowDark,meadowLight,t);colors.setXYZ(i,color.r,color.g,color.b);
      }
      if(colors)colors.needsUpdate=true;
      painted.add(object.geometry);
    }
  });
}
