import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { DRACOLoader } from 'three/addons/loaders/DRACOLoader.js';

export const assetRoot = `${import.meta.env.BASE_URL}cinematic/`;
export function createAssetLoader() {
  const decoder = new DRACOLoader();
  decoder.setDecoderPath(`${assetRoot}decoders/`);
  decoder.setWorkerLimit(2);
  const loader = new GLTFLoader().setDRACOLoader(decoder);
  const controller = new AbortController();
  return {
    model: (name: string) => loader.loadAsync(`${assetRoot}${name}.glb`),
    openingLayout: async (): Promise<{ emitters: number[][]; sensors: number[][] }> => {
      const response = await fetch(`${assetRoot}stylized/estate.json`,{ signal: controller.signal });
      if (!response.ok) throw new Error('The estate layout is unavailable.');
      return response.json();
    },
    dispose: () => { controller.abort(); decoder.dispose(); },
  };
}
