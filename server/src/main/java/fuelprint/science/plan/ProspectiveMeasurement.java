package fuelprint.science.plan;

import fuelprint.api.Enu;
import fuelprint.api.VehicleState;
import fuelprint.api.WindNow;
import fuelprint.api.Window;
import fuelprint.science.rank.*;
import fuelprint.science.sensor.SyntheticNoiseProfile;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Private forecast port: field supplies a simulated approved flight; science applies the measurement operator. */
public record ProspectiveMeasurement(InletTrack track, double backgroundStart, double backgroundEnd,
                                     double collectionStart, double collectionEnd, double completeSeconds,
                                     double energyWh) {
    public static ProspectiveMeasurement from(fuelprint.api.ProspectiveInlets.Forecast forecast) {
        var bg=forecast.stages().stream().filter(s->s.stage().equals("BACKGROUND")).findFirst().orElseThrow();
        var collect=forecast.stages().stream().filter(s->s.stage().equals("COLLECT")).findFirst().orElseThrow();
        if(bg.endS()==null || collect.endS()==null) throw new IllegalArgumentException("Forecast stages must be complete");
        var track=new InletTrack(forecast.samples().stream().map(s->new InletTrack.Sample(s.simTimeS(),s.inlet())).toList(),true);
        return new ProspectiveMeasurement(track,bg.startS()-1,bg.endS()-1,collect.startS()-1,collect.endS()-1,
                forecast.completeSeconds(),forecast.energyWh());
    }

    public double unit(FitEngine engine, Gas gas, String source, Member member, WindNow wind) {
        return unit(engine,gas,source,member,wind,() -> false);
    }

    public double unit(FitEngine engine, Gas gas, String source, Member member, WindNow wind,
                       java.util.function.BooleanSupplier expired) {
        return engine.unitWindow(gas,source,member,wind,track,backgroundStart,backgroundEnd,collectionStart,collectionEnd,expired);
    }

    public double sigma(Gas gas) {
        return Math.sqrt(covariance(List.of(gas))[0][0]);
    }

    /** Both forecast contrasts share the actual common ambient process across gases. */
    public double[][] covariance(List<Gas> gases) {
        List<Double> bg=times(backgroundStart,backgroundEnd), collect=times(collectionStart,collectionEnd);
        var entries=new java.util.ArrayList<WindowSelection.Entry>();
        for (Gas gas : gases) {
            double variance=SyntheticNoiseProfile.meanCovariance(gas.channel(),bg,bg)
                    +SyntheticNoiseProfile.meanCovariance(gas.channel(),collect,collect)
                    -2*SyntheticNoiseProfile.meanCovariance(gas.channel(),bg,collect);
            Window background=new Window("forecast-bg-"+gas,"forecast",gas.channel(),backgroundStart,backgroundEnd,
                    bg.size(),null,1.0,null);
            Window collection=new Window("forecast-col-"+gas,"forecast",gas.channel(),collectionStart,collectionEnd,
                    collect.size(),0.0,Math.sqrt(variance),background.id());
            entries.add(new WindowSelection.Entry(collection,gas,track,background));
        }
        return WindowCovariance.of(entries);
    }

    private static List<Double> times(double start,double end) {
        return IntStream.rangeClosed((int)Math.floor(start)+1,(int)Math.floor(end)).mapToObj(t->(double)t).toList();
    }
}
