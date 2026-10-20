import { describe, it, expect } from 'vitest';
import type { TickVehicle } from '@fp/shared';
import { createEstate } from './estate';
const vehicle: TickVehicle = {vehicle_id:'v1',label:'DRONE-1',kind:'DRONE',pose:{east:10,north:20,up:35,yaw_deg:90},
  inlet:{east:10.4,north:20,up:34.6},velocity:{east:1,north:0,up:0},energy_wh:60,stage:'OUTBOUND',lease_holder:'autopilot',pilot:'AUTOPILOT'};
describe('estate scene uses observed positions',()=>{
  it('moves body and inlet only on observed frames; an empty frame hides both',()=>{
    const e=createEstate();e.update(vehicle);
    expect(e.drone.position.toArray()).toEqual([10,35,-20]);
    expect(e.inlet.position.toArray()).toEqual([10.4,34.6,-20]);
    e.update({...vehicle,pose:{...vehicle.pose,east:30}});
    expect(e.drone.position.x).toBe(30);e.update(null);
    expect(e.drone.visible).toBe(false);expect(e.inlet.visible).toBe(false);e.dispose();
  });
  it('keeps unknown or nonfinite pose hidden and disposes scene resources',()=>{
    const e=createEstate();e.update({...vehicle,pose:{...vehicle.pose,east:NaN}});
    expect(e.drone.visible).toBe(false);
    let disposed=0;e.scene.traverse(o=>{ if ('geometry' in o) (o.geometry as {addEventListener:(type:string,fn:()=>void)=>void}).addEventListener('dispose',()=>disposed++); });
    e.dispose();expect(disposed).toBeGreaterThan(0);
  });
});
