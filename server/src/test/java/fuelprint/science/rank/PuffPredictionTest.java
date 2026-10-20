package fuelprint.science.rank;

import fuelprint.api.Enu;
import fuelprint.api.Window;
import fuelprint.science.sensor.GasSettings;
import fuelprint.science.world.PuffWorld;
import java.util.HashMap;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class PuffPredictionTest {
    @Test void finiteAgeKernelMatchesTheWorldTwentyHzPuffsIncludingLongitudinalDiffusion() {
        var profile=fuelprint.science.world.WorldProfile.load();
        var world=new PuffWorld(profile);
        world.setEmission("S02",1,1000);
        var kernel=new PuffPrediction(WorldProfile.load());
        var inlet=new Enu(120,100,3);
        for(int second=1;second<=200;second++) {
            world.step(1);
            if(second==20||second==100||second==200) {
                double actual=world.concentrationAt(inlet.east(),inlet.north(),inlet.up()).so2Ppb();
                double predicted=kernel.unit(Gas.SO2,-100,100,35,inlet,270,3,second);
                assertThat(predicted).isCloseTo(actual,offset(Math.max(1e-10,actual*1e-9)));
            }
        }
    }

    @Test void fixedNodeDesignSubtractsItsActualEarlyBackgroundAndFiltersTheSamePuffBuildup() {
        var world=new PuffWorld(fuelprint.science.world.WorldProfile.load());
        world.setEmission("S02",1,1000);
        var inlet=new Enu(120,100,3);
        var rng=new SplittableRandom(0);
        var channel=GasSettings.SO2.channel(0,0,0,rng);
        double held=0,bg=0,col=0;
        for(int tick=1;tick<=3000;tick++) {
            world.step(.05);
            if(tick%20==0) held=world.concentrationAt(inlet.east(),inlet.north(),inlet.up()).so2Ppb();
            double value=channel.advance(held,1,.05,false,rng).value();
            if(tick%20==0) {
                int second=tick/20;
                if(second<=30) bg+=value/30;
                if(second>120) col+=value/30;
            }
        }
        var background=new Window("bg","fixed",Gas.SO2.channel(),0,30,30,null,4.2,null);
        var collection=new Window("col","fixed",Gas.SO2.channel(),120,150,30,col-bg,6.0,"bg");
        var entry=new WindowSelection.Entry(collection,Gas.SO2,InletTrack.fixed(inlet),background);
        var engine=new FitEngine(WorldProfile.load(),ResponseModel.TEACHING);
        double predicted=engine.design(Gas.SO2,List.of("S02"),Member.all().get(13),KernelWindows.WIND,
                List.of(entry),new HashMap<>())[0][0];
        assertThat(predicted).isCloseTo(col-bg,offset(1e-8));
    }
}
