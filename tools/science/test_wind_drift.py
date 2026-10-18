"""Run with python3 -I tools/science/test_wind_drift.py."""
import math
from pathlib import Path
import runpy
import unittest
from datetime import datetime, timedelta

wind = runpy.run_path(str(Path(__file__).with_name('wind_drift.py')))


class WindDriftTest(unittest.TestCase):
    def test_circular_delta(self):
        self.assertEqual(wind['angular_delta'](359, 1), 2)
        self.assertEqual(wind['angular_delta'](1, 359), -2)

    def test_mapping_reproduces_increment_variance(self):
        start = datetime(2026, 1, 1, 20, tzinfo=wind['IST'])
        samples = {start + timedelta(minutes=15*i): {'wind_direction': 260 + 10*(i % 2),
                                                    'wind_speed': 3} for i in range(5)}
        result = wind['summarize'](samples)
        self.assertEqual(result['selected_pairs'], 4)
        self.assertEqual(result['direction_change_rms_15min_deg'], 10)
        sigma = result['direction_stationary_sd_deg']
        self.assertAlmostEqual(2*sigma*sigma*(1-wind['RHO']**900), 100)
        self.assertAlmostEqual(result['direction_innovation_sd_per_second_deg'],
                               sigma*math.sqrt(1-wind['RHO']**2))

    def test_both_endpoints_and_gaps_are_filtered(self):
        start = datetime(2026, 1, 1, 20, tzinfo=wind['IST'])
        rows = [(0, 250, 3), (15, 260, 3), (30, 270, 3), (45, 280, 1),
                (60, 290, 3), (90, 300, 3)]
        samples = {start + timedelta(minutes=m): {'wind_direction': d, 'wind_speed': v} for m,d,v in rows}
        result = wind['summarize'](samples)
        self.assertEqual(result['selected_pairs'], 2)

    def test_night_is_used_when_sufficient(self):
        samples = {}
        for day in range(4):
            start = datetime(2026, 1, 1, 20, tzinfo=wind['IST']) + timedelta(days=day)
            for i in range(40):
                samples[start + timedelta(minutes=15*i)] = {'wind_direction': 260 + i % 2, 'wind_speed': 3}
        result = wind['summarize'](samples)
        self.assertEqual(result['hours_ist'], '20:00..05:59')
        self.assertEqual(result['selected_pairs'], 156)


if __name__ == '__main__':
    unittest.main()
