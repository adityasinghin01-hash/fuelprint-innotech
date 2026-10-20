import type { Enu } from '@fp/shared';
/** The contract's only ENU -> Three.js transform. */
export function toScene(p: Enu): [number, number, number] { return [p.east, p.up, -p.north]; }
