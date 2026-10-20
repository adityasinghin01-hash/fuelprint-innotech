import { describe, expect, it } from 'vitest';
import { toScene } from './coordinates';
describe('ENU to scene coordinates', () => {
  it('maps east north up into east up negative north exactly once', () => {
    expect(toScene({ east: 10, north: 20, up: 3 })).toEqual([10, 3, -20]);
    expect(toScene({ east: -4, north: -8, up: 0 })).toEqual([-4, 0, 8]);
  });
});
