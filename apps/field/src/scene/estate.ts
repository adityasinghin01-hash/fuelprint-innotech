import * as THREE from 'three';
import { CSS2DObject } from 'three/addons/renderers/CSS2DRenderer.js';
import type { TickVehicle } from '@fp/shared';
import world from '../../../../contracts/reference/world-profile.json';
import { toScene } from './coordinates';

/** All fixed geometry is from the public world profile; no truth/emissions or generated readings. */
export function createEstate() {
  const scene = new THREE.Scene();scene.background = new THREE.Color('#152a25');
  scene.add(new THREE.HemisphereLight(0xd9eee5, 0x34423c, 2));
  const light = new THREE.DirectionalLight(0xffecd0, 3);light.position.set(300,700,200);scene.add(light);
  function box(w:number,h:number,d:number,color:number) {
    return new THREE.Mesh(new THREE.BoxGeometry(w,h,d),new THREE.MeshStandardMaterial({color,roughness:.8}));
  }
  function label(text:string, at:THREE.Object3D, y:number) {
    const el=document.createElement('span');el.textContent=text;el.className='estate-label';
    const obj=new CSS2DObject(el);obj.position.y=y;at.add(obj);
  }
  const ground=box(1000,1,1000,0x24443a);ground.position.y=-1;scene.add(ground);
  const grid=new THREE.GridHelper(1000,20,0x749887,0x365b4d);scene.add(grid);
  function road(east:number,north:number,w:number,d:number) {
    const mesh=box(w,.2,d,0x79877a);mesh.position.set(...toScene({east,north,up:0}));scene.add(mesh);
  }
  const roads=world.roads;const [x0,x1]=roads.rectangle_x_m;const [n0,n1]=roads.rectangle_y_m;
  for(const n of [n0,n1,roads.central_horizontal_y_m])road((x0+x1)/2,n,x1-x0,roads.width_m);
  for(const e of [x0,x1,roads.central_vertical_x_m])road(e,(n0+n1)/2,roads.width_m,n1-n0);
  road((roads.spur.x_m[0]+roads.spur.x_m[1])/2,roads.spur.y_m,roads.spur.x_m[1]-roads.spur.x_m[0],roads.width_m);
  for(const source of world.sources) {
    const building=box(source.footprint_m[0],source.roof_m,source.footprint_m[1],0x99aa98);
    building.position.set(...toScene({east:source.east_m,north:source.north_m,up:source.roof_m/2}));scene.add(building);
    const stack=box(4,source.effective_height_m,4,0xced7c3);
    stack.position.set(...toScene({east:source.east_m,north:source.north_m,up:source.effective_height_m/2}));scene.add(stack);label(source.id,stack,source.effective_height_m/2+10);
  }
  for(const node of world.nodes) {
    const mesh=box(5,node.inlet_m,5,0xf1b560);mesh.position.set(...toScene({east:node.east_m,north:node.north_m,up:node.inlet_m/2}));scene.add(mesh);label(node.id,mesh,13);
  }
  const drone=new THREE.Group();const body=box(10,3,7,0xe0d9b5);drone.add(body);
  for(const angle of [-Math.PI/4,Math.PI/4]) {const arm=box(23,1,1,0xe0d9b5);arm.rotation.y=angle;drone.add(arm);}
  label('DRONE · visual marker',drone,16);drone.visible=false;scene.add(drone);
  const inlet=new THREE.Mesh(new THREE.SphereGeometry(2,12,8),new THREE.MeshBasicMaterial({color:0x66e7ff}));
  inlet.visible=false;scene.add(inlet);
  function update(vehicle:TickVehicle|null) {
    const valid=vehicle && [vehicle.pose.east,vehicle.pose.north,vehicle.pose.up,vehicle.pose.yaw_deg,vehicle.inlet.east,vehicle.inlet.north,vehicle.inlet.up].every(Number.isFinite);
    drone.visible=!!valid;inlet.visible=!!valid;
    if(!valid || !vehicle)return;
    drone.position.set(...toScene(vehicle.pose));drone.rotation.y=-vehicle.pose.yaw_deg*Math.PI/180;
    inlet.position.set(...toScene(vehicle.inlet));
  }
  function dispose() {
    const geometries=new Set<THREE.BufferGeometry>();const materials=new Set<THREE.Material>();
    scene.traverse(object=>{
      if(object instanceof THREE.Mesh || object instanceof THREE.LineSegments) {geometries.add(object.geometry);for(const material of Array.isArray(object.material)?object.material:[object.material])materials.add(material);}
      if(object instanceof CSS2DObject)object.element.remove();
    });
    geometries.forEach(g=>g.dispose());materials.forEach(m=>m.dispose());scene.clear();
  }
  return {scene,drone,inlet,update,dispose};
}
