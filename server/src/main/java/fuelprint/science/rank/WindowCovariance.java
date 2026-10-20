package fuelprint.science.rank;

import fuelprint.api.Window;
import fuelprint.science.sensor.GasChannel;
import fuelprint.science.sensor.GasSettings;
import fuelprint.science.sensor.SyntheticNoiseProfile;
import java.util.List;
import java.util.SplittableRandom;

/**
 * B K B^T for the actual 1 Hz collection-minus-background operators. Electrical AR1 is independent per
 * instrument/gas; the common ambient AR1 (rho=.95, innovation SD=1, loadings 1/.5) passes through the same
 * line/chamber/gas response before averaging. Initial response holds the time-zero common background.
 * Wind/model discrepancy is not measurement noise. A window lacking a recorded background retains only its
 * declared diagonal variance (legacy fixtures); this approximation is disclosed by the ranker.
 */
public final class WindowCovariance {
    private WindowCovariance() { }

    public static double[][] of(List<WindowSelection.Entry> entries) {
        int n = entries.size();
        int last = (int)Math.ceil(entries.stream().mapToDouble(e -> e.window().endS()).max().orElse(0));
        if (last < 0 || last > 900) throw new IllegalArgumentException("Covariance time outside live envelope");
        double[][] electrical = new double[n][], common = new double[n][];
        if (entries.stream().allMatch(e -> e.background() == null)) {
            double[][] diagonal = new double[n][n];
            for (int i=0;i<n;i++) diagonal[i][i] = entries.get(i).sigma()*entries.get(i).sigma();
            return diagonal;
        }
        double[][] impulse = new double[Gas.values().length][];
        for (Gas gas : Gas.values()) impulse[gas.ordinal()] = impulse(gas, last);
        double[] extra = new double[n];
        for (int i=0; i<n; i++) {
            var e = entries.get(i);
            if (e.background() == null) { extra[i] = e.sigma()*e.sigma(); continue; }
            double[] weights = new double[last+1];
            addWindow(weights, e.window(), 1);
            addWindow(weights, e.background(), -1);
            electrical[i] = innovations(weights, SyntheticNoiseProfile.RHO);
            double sd = SyntheticNoiseProfile.innovationSd(e.window().channel());
            for (int k=0;k<electrical[i].length;k++) electrical[i][k] *= sd;
            double measuredElectrical = dot(electrical[i], electrical[i]);
            extra[i] = Math.max(0, e.sigma()*e.sigma() - measuredElectrical);
            double[] filtered = new double[last+1];
            double[] h = impulse[e.gas().ordinal()];
            double loading = e.gas() == Gas.SO2 ? 1 : .5;
            for (int s=0;s<weights.length;s++) {
                if (weights[s] == 0) continue;
                double total = 0;
                for (int k=1;k<=s;k++) {
                    double v = h[s-k];
                    filtered[k] += loading*weights[s]*v;
                    total += v;
                }
                filtered[0] += loading*weights[s]*(1-total);
            }
            common[i] = innovations(filtered, .95);
        }
        double[][] covariance = new double[n][n];
        for(int i=0;i<n;i++) for(int j=0;j<=i;j++) {
            double value = common[i] == null || common[j] == null ? 0 : dot(common[i],common[j]);
            if(electrical[i] != null && electrical[j] != null
                    && entries.get(i).window().sensorOrVehicleId().equals(entries.get(j).window().sensorOrVehicleId())
                    && entries.get(i).gas() == entries.get(j).gas()) value += dot(electrical[i],electrical[j]);
            if(i==j) value += extra[i];
            covariance[i][j]=covariance[j][i]=value;
        }
        return covariance;
    }

    private static void addWindow(double[] w, Window window, double sign) {
        int first=(int)Math.floor(window.startS()+1e-7)+1, last=(int)Math.floor(window.endS()+1e-7);
        int count=last-first+1;
        if(first<0 || last>=w.length || count<=0) throw new IllegalArgumentException("Invalid sample bounds");
        for(int k=first;k<=last;k++) w[k]+=sign/count;
    }

    /** Coefficients on independent unit innovations, including stationary initial AR1 state. */
    private static double[] innovations(double[] weights, double rho) {
        double[] out=new double[weights.length];
        double sum=0;
        for(int k=weights.length-1;k>=0;k--) { sum=weights[k]+rho*sum; out[k]=sum; }
        out[0]/=Math.sqrt(1-rho*rho);
        return out;
    }

    private static double[] impulse(Gas gas, int last) {
        var rng=new SplittableRandom(0);
        GasChannel channel=GasSettings.valueOf(gas.name()).channel(0,0,0,rng);
        double[] h=new double[last+1];
        double previous=0;
        for(int tick=1;tick<=last*20+1;tick++) {
            double response=channel.advance(1,1,.05,false,rng).value();
            if(tick%20==1) { int k=tick/20; h[k]=response-previous; previous=response; }
        }
        return h;
    }

    private static double dot(double[] a,double[] b) {
        double s=0; for(int k=0;k<a.length;k++) s+=a[k]*b[k]; return s;
    }
}
