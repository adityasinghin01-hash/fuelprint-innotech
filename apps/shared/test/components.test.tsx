import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import * as shared from '../src';
import { AutomationLogList } from '../src/components/AutomationLog';
import { Button } from '../src/components/Button';
import { DoneAutomatically } from '../src/components/DoneAutomatically';
import { LiveBadge } from '../src/components/LiveBadge';
import { OutcomeBadge } from '../src/components/OutcomeBadge';
import { StaleBanner } from '../src/components/StaleBanner';
import { ANALYSIS_OUTCOME_WORDS, FINDING_OUTCOME_WORDS, valueOrUnknown } from '../src/plainWords';
import type { AutomationLogEntry } from '../src/types';

const entry = (id: string, utc: string, type: string, status: 'APPLIED' | 'REJECTED' = 'APPLIED',
  reasonText: string | null = null): AutomationLogEntry => ({
  command_id: id, command_type: type, run_id: 'run', received_utc: utc,
  receipt: { receipt_id: 'r' + id, command_id: id, status, reason_code: status === 'APPLIED' ? null : 'NO_NEW_LESSONS',
    reason_text: reasonText, record_refs: [], actor: 'automation', automatic: true },
});

describe('screen kit', () => {
  it('shows every outcome with plain words and never a percentage', () => {
    const outcomes = [...Object.keys(ANALYSIS_OUTCOME_WORDS), ...Object.keys(FINDING_OUTCOME_WORDS)];
    expect(outcomes).toHaveLength(8);
    for (const outcome of outcomes) {
      const { container, unmount } = render(<OutcomeBadge outcome={outcome as never} />);
      expect(container.textContent).not.toMatch(/%|percent|chance|probab/i);
      expect(container.querySelector('.fp-explain')?.textContent?.length).toBeGreaterThan(10);
      unmount();
    }
    render(<OutcomeBadge outcome="NO_SIGNAL" />);
    expect(screen.getByText(/does NOT mean the factories are clean/)).toBeTruthy();
  });

  it('lists automatic steps newest first, in plain words, with refusals and their reason', () => {
    render(<AutomationLogList entries={[
      entry('1', '2026-10-22T10:00:01.000Z', 'open_investigation'),
      entry('3', '2026-10-22T10:00:09.000Z', 'retrain_model', 'REJECTED', 'SO2 rejected, not a lesson'),
      entry('2', '2026-10-22T10:00:05.000Z', 'propose_mission'),
    ]} />);
    const items = screen.getAllByRole('listitem').map((li) => li.textContent ?? '');
    expect(items).toHaveLength(3);
    expect(items[0]).toMatch(/Not done: retrained the learned ranker\. Reason: SO2 rejected, not a lesson/);
    expect(items[1]).toMatch(/Proposed a drone mission, done automatically/);
    expect(items[2]).toMatch(/Opened an investigation/);
  });

  it('says when nothing was done automatically yet', () => {
    render(<AutomationLogList entries={[]} />);
    expect(screen.getByText(/has not done anything by itself/)).toBeTruthy();
  });

  it('shows the stale banner only when data is not fresh, and the badge for each state', () => {
    const { rerender, queryByTestId } = render(<StaleBanner status="live" />);
    expect(queryByTestId('stale-banner')).toBeNull();
    rerender(<StaleBanner status="stale" />);
    expect(queryByTestId('stale-banner')?.textContent).toMatch(/3 seconds/);
    rerender(<LiveBadge status="offline" />);
    expect(screen.getByTestId('live-badge').textContent).toMatch(/Offline/);
  });

  it('marks automatic steps with their time', () => {
    render(<DoneAutomatically at="2026-10-22T10:15:04.120Z" />);
    expect(screen.getByTestId('done-automatically').textContent).toMatch(/done automatically at \d\d:\d\d:\d\d/);
  });

  it('blocks a pending button and keeps unknown values unknown', () => {
    render(<Button pending>Approve</Button>);
    const button = screen.getByRole('button') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.className).toContain('fp-button');
    expect(valueOrUnknown(null, 'ppb')).toBe('unknown');
    expect(valueOrUnknown(0, 'ppb')).toBe('0.0 ppb');
  });

  it('offers no percentage helper at all', () => {
    expect(Object.keys(shared).filter((name) => /percent|pct|probab|chance/i.test(name))).toEqual([]);
  });
});
