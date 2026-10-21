import * as THREE from 'three';

/** Decorative airflow near the inlet. No chemical identity, quantity or scientific simulation. */
export function createSamplingFlow(drone: THREE.Group) {
  const group=new THREE.Group();drone.add(group);
  const material=new THREE.MeshBasicMaterial({color:'#d2be8d',transparent:true,opacity:0,depthWrite:false});
  const paths=[-.45,0,.45].map((offset)=>new THREE.CubicBezierCurve3(
    new THREE.Vector3(.58+offset,-1.8,1.8),new THREE.Vector3(.58+offset,-1.65,.9),
    new THREE.Vector3(.58+offset*.2,-1.35,-.1),new THREE.Vector3(.58,-1.02,-.2),
  ));
  const ribbons=paths.map((path)=>new THREE.Mesh(new THREE.TubeGeometry(path,28,.012,5,false),material));
  ribbons.forEach((ribbon)=>group.add(ribbon));
  const geometry=new THREE.IcosahedronGeometry(.045,1),dots=new THREE.InstancedMesh(geometry,material,18);
  dots.frustumCulled=false;group.add(dots);
  const dummy=new THREE.Object3D();
  return {
    update(time: number,amount: number) {
      group.visible=amount>.001;material.opacity=amount*.7;
      for(let i=0;i<18;i++) {
        const t=(time*.3+i/6)%1;
        dummy.position.copy(paths[i%3].getPoint(t));dummy.scale.setScalar(Math.sin(t*Math.PI));dummy.updateMatrix();dots.setMatrixAt(i,dummy.matrix);
      }
      dots.instanceMatrix.needsUpdate=true;
    },
    dispose() {drone.remove(group);ribbons.forEach((ribbon)=>ribbon.geometry.dispose());geometry.dispose();material.dispose();},
  };
}
