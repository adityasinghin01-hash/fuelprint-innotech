import { cleanup, render } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import StoryScene from './StoryScene';

vi.mock('three',async(importOriginal)=>({
  ...await importOriginal<typeof import('three')>(),
  WebGLRenderer:class { constructor(){throw new Error('WebGL unavailable');} },
}));
afterEach(cleanup);

it('reports renderer failure so the full static story can remain accessible',()=>{
  const onReady=vi.fn(),onUnavailable=vi.fn();
  const {container}=render(<StoryScene progress={{current:{value:0}}} paused={false} onReady={onReady} onUnavailable={onUnavailable}/>);
  expect(onUnavailable).toHaveBeenCalledOnce();
  expect(onReady).not.toHaveBeenCalled();
  expect(container.querySelector('canvas')).toBeNull();
});
