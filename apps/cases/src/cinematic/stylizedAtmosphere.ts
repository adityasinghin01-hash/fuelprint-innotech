import * as THREE from 'three';
import direction from './artDirection.json';

/** Art-directed atmosphere; no dispersion calculations or Field simulation. */
export function createAtmosphere(scene: THREE.Scene, emitters: number[][], compact: boolean) {
  const meshes: THREE.Mesh[] = [], materials: THREE.ShaderMaterial[] = [];
  const cloudGeo = new THREE.BoxGeometry(2,2,2);
  const clouds = [[-74,17,20,27,9,18],[70,19,36,30,10,17],[7,19,-93,45,10,18],[-71,25,-58,33,10,18],[91,23,-46,31,9,15],[-29,12,76,26,7,15]];
  clouds.forEach(([x,y,z,sx,sy,sz],index) => {
    const material = new THREE.ShaderMaterial({
      uniforms: { eye: { value: new THREE.Vector3() }, time: { value: 0 }, veil: { value: 1 }, lightColor: { value: new THREE.Color(direction.cloudLight) }, shadeColor: { value: new THREE.Color(direction.cloudShade) } },
      transparent: true, depthWrite: false, side: THREE.BackSide,
      vertexShader: 'varying vec3 localPosition;void main(){localPosition=position;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0);}',
      fragmentShader: `
        precision highp float; varying vec3 localPosition; uniform vec3 eye; uniform float time; uniform float veil;
        uniform vec3 lightColor; uniform vec3 shadeColor;
        float ellipsoid(vec3 p,vec3 c,vec3 r){return length((p-c)/r)-1.0;}
        float density(vec3 p){
          float d=min(ellipsoid(p,vec3(-.38,-.16,0.),vec3(.59,.46,.62)),ellipsoid(p,vec3(.08,.12,.03),vec3(.58,.70,.72)));
          d=min(d,ellipsoid(p,vec3(.57,-.20,0.),vec3(.40,.38,.54)));
          d=min(d,ellipsoid(p,vec3(-.04,-.35,.02),vec3(.79,.37,.66)));
          d+=.025*sin(p.x*16.+time*.12)*sin(p.z*13.)*sin(p.y*12.);
          return 1.-smoothstep(-.10,.15,d);
        }
        void main(){
          vec3 ray=normalize(localPosition-eye);vec3 inv=1./(ray+vec3(.000001));
          vec3 a=(-vec3(1.)-eye)*inv,b=(vec3(1.)-eye)*inv;
          vec3 lo=min(a,b),hi=max(a,b);float near=max(max(lo.x,lo.y),lo.z),far=min(min(hi.x,hi.y),hi.z);
          near=max(0.,near);if(far<=near)discard;
          float stepSize=(far-near)/${compact ? '16.' : '24.'};vec4 total=vec4(0.);
          for(int i=0;i<${compact ? '16' : '24'};i++){
            vec3 p=eye+ray*(near+(float(i)+.5)*stepSize);float d=density(p);float alpha=1.-exp(-d*stepSize*2.4);
            vec3 color=mix(shadeColor,lightColor,smoothstep(-.8,.6,p.y)*.8+.2);
            total.rgb+=(1.-total.a)*color*alpha;total.a+=(1.-total.a)*alpha;
          }
          if(total.a<.012)discard;gl_FragColor=vec4(total.rgb/max(total.a,.001),total.a*${direction.cloudOpacity.toFixed(2)}*veil);
          #include <tonemapping_fragment>
          #include <colorspace_fragment>
        }`,
    });
    const mesh = new THREE.Mesh(cloudGeo,material);mesh.position.set(x,y,z);mesh.scale.set(sx,sy,sz);mesh.renderOrder=index;
    scene.add(mesh);mesh.updateMatrixWorld();meshes.push(mesh);materials.push(material);
  });
  const perChimney = compact ? 6 : 9, count = emitters.length * perChimney;
  const smokeGeo = new THREE.PlaneGeometry(1,1);
  const smokeMaterial = new THREE.ShaderMaterial({
    transparent: true, depthWrite: false,
    uniforms: { smokeColor: { value: new THREE.Color(direction.smoke) } },
    vertexShader: `
      varying vec2 vUv; varying float opacity;
      void main(){vUv=uv;opacity=instanceColor.r;vec4 center=modelViewMatrix*instanceMatrix*vec4(0.,0.,0.,1.);
        center.xy+=position.xy*vec2(length(instanceMatrix[0].xyz),length(instanceMatrix[1].xyz));gl_Position=projectionMatrix*center;}`,
    fragmentShader: `
      uniform vec3 smokeColor;varying vec2 vUv;varying float opacity;
      void main(){vec2 p=vUv-.5;float r=length(p+vec2(.035*sin(p.y*15.),.025*sin(p.x*12.)));
        float alpha=(1.-smoothstep(.04,.49,r))*opacity*.48;if(alpha<.008)discard;
        gl_FragColor=vec4(smokeColor,alpha);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
      }`,
  });
  const smoke = new THREE.InstancedMesh(smokeGeo,smokeMaterial,count);smoke.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
  smoke.frustumCulled=false;smoke.renderOrder=10;
  const dummy = new THREE.Object3D(),color = new THREE.Color();
  for (let i=0;i<count;i++) smoke.setColorAt(i,color.setRGB(1,1,1));
  scene.add(smoke);
  return {
    update(time: number,camera: THREE.Camera) {
      meshes.forEach((mesh,i) => {
        mesh.position.x=clouds[i][0]+Math.sin(time*.025+i)*2.8;mesh.updateMatrixWorld();
        materials[i].uniforms.eye.value.copy(camera.position);mesh.worldToLocal(materials[i].uniforms.eye.value);materials[i].uniforms.time.value=time;
      });
      emitters.forEach(([x,y,z],e) => {
        for (let j=0;j<perChimney;j++) {
          const age=(time*.055+j/perChimney+e*.137)%1,scale=1.4+age*4.3;
          dummy.position.set(x+age*age*7,y+age*12,z+age*2);dummy.scale.set(scale,scale*.85,1);dummy.updateMatrix();smoke.setMatrixAt(e*perChimney+j,dummy.matrix);
          const opacity=Math.pow(Math.sin(age*Math.PI),.65);smoke.setColorAt(e*perChimney+j,color.setRGB(opacity,opacity,opacity));
        }
      });
      smoke.instanceMatrix.needsUpdate=true;smoke.instanceColor!.needsUpdate=true;
    },
    dispose() {
      meshes.forEach((mesh) => scene.remove(mesh));scene.remove(smoke);cloudGeo.dispose();smokeGeo.dispose();smokeMaterial.dispose();materials.forEach((m) => m.dispose());
    },
  };
}
