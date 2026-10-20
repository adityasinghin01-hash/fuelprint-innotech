import { Explain } from '@fp/shared';
import type { ReadingRecord, SensorRecord, TickMessage } from '@fp/shared';

export function ReadingPanel({ frame, readings, sensors }: { frame: TickMessage | null; readings: ReadingRecord[]; sensors: SensorRecord[] }) {
  return <section aria-label="Live readings"><h2>Observed readings</h2>
    <Explain what="Values, channel QC and times from the same server frame as the drone. Origin comes only from a matching saved reading." />
    {!frame?.payload.latest_readings.length ? <p>No readings in this server frame. Values are unknown.</p> :
      <div className="table-scroll"><table><caption>Server observations · run {frame.run_id}</caption>
        <thead><tr><th>Instrument</th><th>Channel</th><th>Value</th><th>QC</th><th>Origin</th><th>Time (s)</th></tr></thead>
        <tbody>{frame.payload.latest_readings.map((r, i) => {
          const sensorId = sensors.find(s => s.label === r.sensor)?.id ?? r.sensor;
          const saved = readings.find(s => s.run_id === frame.run_id && s.sensor_or_vehicle_id === sensorId
            && s.channel === r.channel && s.sim_time_s === r.sim_time_s && s.value === r.value);
          const unit = saved?.unit ?? ({ SO2_ppb: 'ppb', CO2_ppm: 'ppm', PM_ug_m3: 'ug_m3 (optical estimate)' } as Record<string, string>)[r.channel] ?? 'unit unknown';
          return <tr key={`${r.sensor}-${r.channel}-${i}`}><td>{r.sensor}</td><td>{r.channel}</td>
            <td>{r.value === null || !Number.isFinite(r.value) ? 'unknown (no valid value)' : `${r.value} ${unit}`}</td>
            <td>{r.quality.join(', ') || 'unknown (QC not supplied)'}</td>
            <td>{saved?.origin ?? 'origin unknown (matching stored reading not received)'}</td><td>{r.sim_time_s}</td></tr>;
        })}</tbody>
      </table></div>}
  </section>;
}
