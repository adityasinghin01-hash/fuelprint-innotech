import { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { ArrowCounterClockwise, Cube, Pause, Play, Scan, Tree } from './icons';

type View = 'estate' | 'sensors' | 'air';

/** An illustrative model, deliberately separate from Field's simulation and hidden source truth. */
export default function EstateScene() {
  const host = useRef<HTMLDivElement>(null);
  const viewRef = useRef<View>('estate');
  const pausedRef = useRef(false);
  const resetRef = useRef<() => void>(() => undefined);
  const [view, setView] = useState<View>('estate');
  const [paused, setPaused] = useState(false);
  const [available, setAvailable] = useState(true);

  useEffect(() => {
    const element = host.current;
    if (!element || /jsdom/i.test(navigator.userAgent)) return;
    let renderer: THREE.WebGLRenderer;
    try { renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' }); }
    catch { setAvailable(false); return; }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5));
    renderer.setClearColor(0x000000, 0);
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFShadowMap;
    renderer.shadowMap.autoUpdate = false;
    renderer.shadowMap.needsUpdate = true;
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    element.appendChild(renderer.domElement);
    renderer.domElement.setAttribute('aria-hidden', 'true');
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(34, 1, 0.1, 180);
    const world = new THREE.Group();
    world.rotation.y = -0.2;
    scene.add(world);
    scene.add(new THREE.HemisphereLight(0xf5fff4, 0xb7c3a2, 2.8));
    const sun = new THREE.DirectionalLight(0xfff4df, 4.2);
    sun.position.set(-12, 23, 16);
    sun.castShadow = true;
    sun.shadow.mapSize.set(1024, 1024);
    Object.assign(sun.shadow.camera, { left: -20, right: 20, top: 20, bottom: -20, far: 80 });
    sun.shadow.bias = -0.0007;
    scene.add(sun);

    const material = (color: number, roughness = 0.85) => new THREE.MeshStandardMaterial({ color, roughness });
    const grass = material(0xa9c787), meadow = material(0xc5d8ac), edge = material(0xdad7c5);
    const trunk = material(0x786c4d), leaf = [material(0x4e7656), material(0x719865), material(0x87a973)];
    const cream = material(0xf5f2e7), roof = material(0x748e80), road = material(0xe6e8dd);
    const ink = material(0x294e40), copper = material(0xb87648), water = material(0xa6c7ba, 0.36);
    const addBox = (x: number, y: number, z: number, w: number, h: number, d: number, mat: THREE.Material, parent: THREE.Object3D = world) => {
      const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
      mesh.position.set(x, y, z); mesh.castShadow = true; mesh.receiveShadow = true; parent.add(mesh); return mesh;
    };
    const cylinder = (x: number, y: number, z: number, top: number, bottom: number, height: number, mat: THREE.Material, parent: THREE.Object3D = world, sides = 16) => {
      const mesh = new THREE.Mesh(new THREE.CylinderGeometry(top, bottom, height, sides), mat);
      mesh.position.set(x, y, z); mesh.castShadow = true; mesh.receiveShadow = true; parent.add(mesh); return mesh;
    };
    // A cutaway piece of land: the scene is a teaching illustration, not a surveyed map.
    addBox(0, -0.9, 0, 23, 1.6, 18, edge);
    addBox(0, -0.02, 0, 23.12, 0.22, 18.12, meadow);
    addBox(-5.1, 0.12, -3.8, 11.5, 0.08, 7.6, grass);
    addBox(6.5, 0.1, 5.8, 7, 0.08, 4.2, grass);
    addBox(0.8, 0.17, 0, 1.45, 0.08, 18.2, road);
    addBox(0, 0.18, 1.2, 23.15, 0.08, 1.3, road);
    addBox(-6, 0.19, -4.5, 0.95, 0.08, 9, road);
    addBox(6, 0.18, -3.2, 9.2, 0.07, 0.8, road);
    for (let i = -8; i < 9; i += 1.6) addBox(0.8, 0.23, i, 0.06, 0.02, 0.6, cream);
    for (let i = -10; i < 11; i += 1.6) addBox(i, 0.24, 1.2, 0.6, 0.02, 0.06, cream);

    const tree = (x: number, z: number, size = 1) => {
      cylinder(x, 0.45 * size, z, 0.08 * size, 0.12 * size, 0.8 * size, trunk);
      const crown = new THREE.Mesh(new THREE.IcosahedronGeometry(0.65 * size, 1), leaf[Math.abs(Math.floor(x + z)) % 3]);
      crown.position.set(x, 1.15 * size, z); crown.scale.y = 1.35; crown.castShadow = true; world.add(crown);
    };
    for (let i = 0; i < 55; i++) {
      const x = -10.6 + ((i * 7.33) % 21.2), z = -8 + ((i * 4.71) % 16);
      if (Math.abs(x - 0.8) > 1.5 && Math.abs(z - 1.2) > 1.35 && (z > 3 || x < -8.6 || z < -6)) tree(x, z, 0.7 + (i % 4) * 0.12);
    }
    // Six fictional site buildings: none is marked as the emitting source.
    const sites = [[-8.5, -3.5], [-3.3, -3.5], [5.2, -5.1], [6.8, 3.8], [-6.5, 4.4], [4.5, 6.8]];
    sites.forEach(([x, z], index) => {
      const width = index === 2 ? 3.5 : 2.8, depth = index === 5 ? 1.8 : 2.3;
      addBox(x, 0.2, z, width + 0.9, 0.12, depth + 0.9, cream);
      addBox(x, 0.82, z, width, 1.15, depth, cream);
      addBox(x, 1.45, z, width + 0.1, 0.15, depth + 0.1, roof);
      for (let w = 0; w < 3; w++) addBox(x - 0.85 + w * 0.8, 0.86, z + depth / 2 + 0.015, 0.42, 0.35, 0.03, ink);
      cylinder(x + width / 2 - 0.3, 1.8, z - depth / 2 + 0.3, 0.14, 0.2, 2.6, cream);
      cylinder(x + width / 2 - 0.3, 3.1, z - depth / 2 + 0.3, 0.16, 0.16, 0.14, copper);
      if (index < 3) for (let s = 0; s < 3; s++) {
        const panel = addBox(x - 0.8 + s * 0.8, 1.6, z, 0.65, 0.07, 1.2, ink); panel.rotation.x = -0.12;
      }
    });
    const pond = new THREE.Mesh(new THREE.CylinderGeometry(2, 2, 0.08, 48), water);
    pond.position.set(-3, 0.18, 6.2); pond.scale.z = 0.6; world.add(pond);
    const sensors = new THREE.Group(); world.add(sensors);
    [[-2, -0.5], [3.2, -1.5], [9.1, -4], [9.1, 0.1]].forEach(([x, z]) => {
      cylinder(x, 0.8, z, 0.045, 0.045, 1.6, ink, sensors);
      addBox(x, 1.7, z, 0.38, 0.5, 0.3, cream, sensors);
      const ring = new THREE.Mesh(new THREE.TorusGeometry(0.47, 0.025, 8, 40), copper);
      ring.rotation.x = Math.PI / 2; ring.position.set(x, 0.26, z); sensors.add(ring);
    });
    const drone = new THREE.Group(); world.add(drone);
    addBox(0, 0, 0, 0.65, 0.2, 0.45, cream, drone);
    const rotors: THREE.Object3D[] = [];
    for (const x of [-0.55, 0.55]) for (const z of [-0.5, 0.5]) {
      const arm = addBox(x / 2, 0, z / 2, 0.85, 0.06, 0.07, ink, drone); arm.rotation.y = x * z > 0 ? -0.7 : 0.7;
      cylinder(x, 0.06, z, 0.12, 0.12, 0.15, ink, drone);
      rotors.push(addBox(x, 0.16, z, 0.75, 0.025, 0.07, ink, drone));
    }
    addBox(0, -0.26, 0, 0.25, 0.28, 0.25, copper, drone);
    drone.position.set(3.4, 5, 0);
    const air = new THREE.Group(); world.add(air);
    for (let i = 0; i < 6; i++) {
      const points = Array.from({ length: 25 }, (_, j) => new THREE.Vector3(-9 + j * 0.75, 3.8 + i * 0.25, -4 + i * 1.5 + Math.sin(j * 0.25) * 0.5));
      const line = new THREE.Line(new THREE.BufferGeometry().setFromPoints(points), new THREE.LineBasicMaterial({ color: 0x89aa98, transparent: true, opacity: 0.3 }));
      air.add(line);
    }

    let pointerX = 0, pointerY = 0, rotation = -0.2, dragging = false, lastX = 0, active = true, frameId = 0, lastFrame = 0;
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
    const resize = () => {
      const { width, height } = element.getBoundingClientRect();
      renderer.setSize(width, height); camera.aspect = width / Math.max(1, height); camera.updateProjectionMatrix();
    };
    const resizeObserver = new ResizeObserver(resize); resizeObserver.observe(element); resize();
    const observer = new IntersectionObserver(([entry]) => { active = entry.isIntersecting; }); observer.observe(element);
    const move = (e: PointerEvent) => {
      const bounds = element.getBoundingClientRect();
      pointerX = ((e.clientX - bounds.left) / bounds.width - 0.5) * 2;
      pointerY = ((e.clientY - bounds.top) / bounds.height - 0.5) * 2;
      if (dragging) { rotation += (e.clientX - lastX) * 0.004; renderer.shadowMap.needsUpdate = true; }
      lastX = e.clientX;
    };
    const down = (e: PointerEvent) => { dragging = true; lastX = e.clientX; };
    const up = () => { dragging = false; };
    element.addEventListener('pointermove', move); element.addEventListener('pointerdown', down); window.addEventListener('pointerup', up);
    resetRef.current = () => { rotation = -0.2; pointerX = 0; pointerY = 0; renderer.shadowMap.needsUpdate = true; };
    const startedAt = performance.now();
    const target = new THREE.Vector3();
    const draw = () => {
      frameId = requestAnimationFrame(draw);
      if (!active || document.hidden) return;
      const now = performance.now();
      if (now - lastFrame < 33) return;
      lastFrame = now;
      const t = (performance.now() - startedAt) / 1000, moving = !pausedRef.current && !reduced.matches;
      const selected = viewRef.current;
      const distance = selected === 'estate' ? 34 : selected === 'sensors' ? 28 : 31;
      target.set(selected === 'air' ? 1 : 0, selected === 'air' ? 3 : 0, 0);
      camera.position.lerp(new THREE.Vector3(distance * 0.72 + (moving ? pointerX * 0.7 : 0), distance * 0.6 + (moving ? pointerY * 0.3 : 0), distance * 0.84), 0.04);
      camera.lookAt(target);
      world.rotation.y += (rotation - world.rotation.y) * 0.06;
      if (moving) {
        drone.position.set(3 + Math.sin(t * 0.23) * 1.8, 5.1 + Math.sin(t * 1.2) * 0.13, -1 + Math.cos(t * 0.23));
        rotors.forEach((rotor, i) => { rotor.rotation.y = t * (i % 2 ? -35 : 35); });
        air.position.x = Math.sin(t * 0.25) * 0.3;
      }
      air.visible = selected === 'air'; sensors.scale.setScalar(selected === 'sensors' ? 1.1 : 1);
      renderer.render(scene, camera);
    };
    camera.position.set(26, 24, 32); draw();
    return () => {
      cancelAnimationFrame(frameId); resizeObserver.disconnect(); observer.disconnect();
      element.removeEventListener('pointermove', move); element.removeEventListener('pointerdown', down); window.removeEventListener('pointerup', up);
      scene.traverse((object) => {
        if (object instanceof THREE.Mesh || object instanceof THREE.Line) {
          object.geometry.dispose();
          (Array.isArray(object.material) ? object.material : [object.material]).forEach((m) => m.dispose());
        }
      });
      renderer.dispose(); renderer.domElement.remove();
    };
  }, []);

  return <div className="estate-stage">
    <div className="estate-coordinate"><span className="location-dot" /> Kundli, India <span>28.87° N · 77.12° E</span></div>
    <div className="estate-canvas" ref={host} role="img" aria-label="Interactive illustrative 3D estate with fictional industrial sites, trees, four sensor stations and a sampling drone. Drag to rotate.">
      {!available && <div className="scene-fallback"><Tree size={80} weight="light" /><strong>A living landscape.<br />A considered investigation.</strong><p>The 3D view is unavailable on this device. All casework is still accessible below.</p></div>}
    </div>
    <div className="scene-label scene-label-drone"><span className="scene-dot" /> Sampling drone <small>Illustrative flight</small></div>
    <div className="scene-label scene-label-sensor"><Scan size={17} /> Sensor station <small>Each channel checked separately</small></div>
    <div className="scene-controls" aria-label="Estate view">
      {([{ id: 'estate', label: 'Estate', Icon: Cube }, { id: 'sensors', label: 'Sensors', Icon: Scan }, { id: 'air', label: 'Air paths', Icon: Tree }] as const).map(({ id, label, Icon }) =>
        <button key={id} aria-pressed={view === id} onClick={() => { setView(id); viewRef.current = id; }}><Icon size={16} />{label}</button>)}
      <button className="icon-button" aria-label="Reset estate view" onClick={() => resetRef.current()}><ArrowCounterClockwise size={17} /></button>
      <button className="icon-button" aria-label={paused ? 'Play illustration' : 'Pause illustration'} onClick={() => { setPaused(!paused); pausedRef.current = !paused; }}>{paused ? <Play size={16} /> : <Pause size={16} />}</button>
    </div>
    <p className="scene-disclaimer">Illustrative estate · not a measurement or a source prediction</p>
  </div>;
}
