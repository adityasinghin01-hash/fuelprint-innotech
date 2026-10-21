import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { StoryLanding } from './StoryLanding';

afterEach(()=>{cleanup();vi.unstubAllGlobals();});

it('keeps the complete story and Cases entry readable under the reduced-motion preference',()=>{
  vi.stubGlobal('matchMedia',vi.fn((query: string)=>({
    matches:query==='(prefers-reduced-motion: reduce)',media:query,onchange:null,
    addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn(),dispatchEvent:vi.fn(),
  })));
  const {container}=render(<MemoryRouter><StoryLanding /></MemoryRouter>);
  expect(container.querySelector('.is-live')).toBeNull();
  expect(container.querySelector('.cinematic-renderer')).toBeNull();
  expect(screen.getByRole('heading',{name:'FUELPRINT'})).toBeTruthy();
  expect(screen.getByRole('heading',{name:'A signal arrives. Questions open.'})).toBeTruthy();
  expect(screen.getByRole('heading',{name:'Back to HQ. Carry the context.'})).toBeTruthy();
  expect(screen.queryByRole('button',{name:'Pause atmosphere animation'})).toBeNull();
  expect(screen.getByRole('link',{name:'Enter Cases workspace'}).getAttribute('href')).toBe('#casework');
  expect(screen.getByText('Purge the inlet, allow settling, then collect.')).toBeTruthy();
  const target=container.querySelectorAll('.opening-beat')[10] as HTMLElement;
  target.scrollIntoView=vi.fn();
  fireEvent.change(screen.getByRole('combobox',{name:'Story chapter'}),{target:{value:'10'}});
  expect(target.scrollIntoView).toHaveBeenCalledWith({behavior:'instant'});
});
