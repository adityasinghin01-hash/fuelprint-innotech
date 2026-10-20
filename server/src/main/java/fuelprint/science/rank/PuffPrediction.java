package fuelprint.science.rank;

import fuelprint.api.Enu;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public forward hypothesis: a constant unit emission from run time zero, under one candidate wind/height.
 * Integrates the same finite-age, longitudinally diffusing, reflected 20 Hz puffs as PuffWorld; no truth input.
 * Moving inlets use their recorded positions. Stationary receptor series are cached with a bounded 8 MB envelope.
 */
public final class PuffPrediction {
    private static final double DT=.05;
    private final double sigma0,kx,kz,temperature,pressure;
    private record Key(double east,double north,double height,Enu inlet,double from,double speed) { }
    private final Map<Key,double[]> stationary=new LinkedHashMap<>(64,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key,double[]> e) { return size()>1024; }
    };

    public PuffPrediction(WorldProfile world) {
        sigma0=world.nominal("sigma0_sq_m2"); kx=world.nominal("Kx_m2_s"); kz=world.nominal("Kz_m2_s");
        temperature=world.nominal("temperature_K"); pressure=world.nominal("pressure_Pa");
    }

    public double unit(Gas gas,double east,double north,double height,Enu inlet,double from,double speed,double time) {
        double[] bounds=bounds(east,north,inlet,from,speed);
        int first=Math.max(1,(int)Math.ceil(bounds[0]/DT-1e-9));
        int last=Math.min((int)Math.floor(time/DT+1e-9),(int)Math.floor(bounds[1]/DT+1e-9));
        double sum=0;
        double de=-Math.sin(Math.toRadians(from))*speed, dn=-Math.cos(Math.toRadians(from))*speed;
        for(int k=first;k<=last;k++) sum+=density(east,north,height,inlet,de,dn,k*DT)*DT;
        return convert(gas,sum);
    }

    /** 0..900 s unit-emission concentration in g/m3; arrays are internal read-only cache entries. */
    synchronized double[] stationarySeries(double east,double north,double height,Enu inlet,double from,double speed) {
        Key key=new Key(east,north,height,inlet,from,speed);
        double[] known=stationary.get(key);
        if(known!=null) return known;
        double[] values=new double[901];
        double[] bounds=bounds(east,north,inlet,from,speed);
        int first=Math.max(1,(int)Math.ceil(bounds[0]/DT-1e-9));
        int last=Math.min(18000,(int)Math.floor(bounds[1]/DT+1e-9));
        double de=-Math.sin(Math.toRadians(from))*speed,dn=-Math.cos(Math.toRadians(from))*speed;
        for(int k=first;k<=last;k++) values[(k+19)/20]+=density(east,north,height,inlet,de,dn,k*DT)*DT;
        for(int second=1;second<values.length;second++) values[second]+=values[second-1];
        stationary.put(key,values);
        return values;
    }

    double convert(Gas gas,double grams) { return gas.fromGramsPerM3(grams,temperature,pressure); }

    private double[] bounds(double east,double north,Enu p,double from,double speed) {
        double re=p.east()-east,rn=p.north()-north;
        double along=-re*Math.sin(Math.toRadians(from))-rn*Math.cos(Math.toRadians(from));
        double b=-2*along*speed-80*kx,c=re*re+rn*rn-40*sigma0;
        double disc=b*b-4*speed*speed*c;
        if(disc<0) return new double[]{1,0};
        double root=Math.sqrt(disc);
        return new double[]{(-b-root)/(2*speed*speed),(-b+root)/(2*speed*speed)};
    }

    private double density(double east,double north,double height,Enu p,double de,double dn,double age) {
        double variance=sigma0+2*kx*age,verticalVariance=sigma0+2*kz*age;
        double dx=p.east()-east-de*age,dy=p.north()-north-dn*age;
        double r2=dx*dx+dy*dy;
        if(r2>40*variance) return 0;
        double a=(p.up()-height)*(p.up()-height)/(2*verticalVariance);
        double b=(p.up()+height)*(p.up()+height)/(2*verticalVariance);
        if(Math.min(a,b)>20) return 0;
        return Math.exp(-r2/(2*variance))/(2*Math.PI*variance)
                *(Math.exp(-a)+Math.exp(-b))/Math.sqrt(2*Math.PI*verticalVariance);
    }
}
