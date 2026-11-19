package com.infinifolder.audio.processing;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.InterruptedIOException;
import java.util.Arrays;

public class PcmEffectsTest {
    private static final int RATE=44100;
    private float[] tone(double...hz){float[] out=new float[RATE];for(int i=0;i<out.length;i++)for(double f:hz)out[i]+=(float)(.2*Math.sin(2*Math.PI*f*i/RATE));return out;}
    private double amplitude(float[] x,double hz){double re=0,im=0;int start=RATE/2;for(int i=start;i<x.length;i++){re+=x[i]*Math.cos(2*Math.PI*hz*i/RATE);im+=x[i]*Math.sin(2*Math.PI*hz*i/RATE);}return 2*Math.hypot(re,im)/(x.length-start);}
    @Test public void inversionChangesOnlySelectedSamplesAndIsReversible()throws Exception{
        float[] input={.1f,.2f,-.3f,.4f};float[] out=PcmEffects.invert(input,2,1,2);
        assertArrayEquals(new float[]{.1f,.2f,.3f,-.4f},out,0);assertArrayEquals(input,PcmEffects.invert(out,2,1,2),0);
        assertArrayEquals(new float[]{.1f,.2f,-.3f,.4f},input,0);
    }
    @Test public void linkedNormalizationPreservesBalanceAndOptionalDcRemoval()throws Exception{
        float[] input={.1f,.2f,-.1f,-.2f};float[] linked=PcmEffects.normalize(input,2,0,2,-1,true,false);
        double target=Math.pow(10,-1.0/20);assertEquals(target,linked[1],1e-6);assertEquals(linked[1]/2,linked[0],1e-7);
        float[] independent=PcmEffects.normalize(input,2,0,2,-1,false,false);assertEquals(independent[1],independent[0],1e-7);
        assertArrayEquals(new float[4],PcmEffects.normalize(new float[]{.3f,.1f,.3f,.1f},2,0,2,-1,true,true),0);
        assertArrayEquals(new float[4],PcmEffects.normalize(new float[4],2,0,2,0,true,false),0);
    }
    @Test public void filtersRemoveChosenFrequenciesWithoutRemovingTheWantedTone()throws Exception{
        float[] input=tone(60,1000,12000);
        float[] low=PcmEffects.filter(input,1,RATE,0,RATE,PcmEffects.Filter.LOW_PASS,3000,Math.sqrt(.5));
        assertTrue(amplitude(low,12000)<amplitude(input,12000)*.04);assertTrue(amplitude(low,1000)>.18);
        float[] high=PcmEffects.filter(input,1,RATE,0,RATE,PcmEffects.Filter.HIGH_PASS,200,Math.sqrt(.5));
        assertTrue(amplitude(high,60)<amplitude(input,60)*.1);assertTrue(amplitude(high,1000)>.19);
        float[] notch=PcmEffects.filter(input,1,RATE,0,RATE,PcmEffects.Filter.NOTCH,60,10);
        assertTrue(amplitude(notch,60)<amplitude(input,60)*.02);assertTrue(amplitude(notch,1000)>.19);
    }
    @Test public void filterSelectionPreservesOutsideAndNeverLeaksAcrossChannels()throws Exception{
        float[] input=new float[RATE*2];float[] signal=tone(500,10000);for(int i=0;i<RATE;i++)input[i*2]=signal[i];
        int a=1000,b=RATE-1000;float[] out=PcmEffects.filter(input,2,RATE,a,b,PcmEffects.Filter.LOW_PASS,2000,.707);
        assertArrayEquals(Arrays.copyOfRange(input,0,a*2),Arrays.copyOfRange(out,0,a*2),0);
        assertArrayEquals(Arrays.copyOfRange(input,b*2,input.length),Arrays.copyOfRange(out,b*2,out.length),0);
        for(int i=0;i<RATE;i++)assertEquals(0,out[i*2+1],0);
        assertFalse(Arrays.equals(input,out));assertEquals(input[a*2],out[a*2],0);assertEquals(input[(b-1)*2],out[(b-1)*2],0);
    }
    @Test public void gateReducesQuietNoiseAndKeepsStereoLinked()throws Exception{
        float[] input=new float[RATE*2];for(int i=0;i<RATE;i++){float v=(float)((i<RATE/2?.0001:.2)*Math.sin(2*Math.PI*1000*i/RATE));input[i*2]=v;input[i*2+1]=v/2;}
        float[] out=PcmEffects.noiseGate(input,2,RATE,0,RATE,-40,40,1,30,10);
        double quiet=0,signal=0;for(int f=RATE/4;f<RATE/2;f++)quiet=Math.max(quiet,Math.abs(out[f*2]));for(int f=RATE*3/4;f<RATE;f++)signal=Math.max(signal,Math.abs(out[f*2]));
        assertTrue(quiet<.000002);assertTrue(signal>.19);for(int i=0;i<RATE;i++)assertEquals(out[i*2]/2,out[i*2+1],1e-8);
    }
    @Test public void invalidInputsAndCancellationDoNotProducePartialResults()throws Exception{
        assertThrows(IllegalArgumentException.class,()->PcmEffects.filter(new float[20],1,RATE,0,20,PcmEffects.Filter.NOTCH,RATE/2.0,1));
        assertThrows(IllegalArgumentException.class,()->PcmEffects.invert(new float[]{Float.NaN},1,0,1));
        assertThrows(IllegalArgumentException.class,()->PcmEffects.normalize(new float[2],1,0,2,2,true,false));
        assertThrows(IllegalArgumentException.class,()->PcmEffects.noiseGate(new float[2],1,RATE,0,2,-40,30,-1,10,20));
        Thread.currentThread().interrupt();try{assertThrows(InterruptedIOException.class,()->PcmEffects.invert(new float[10],1,0,10));}finally{Thread.interrupted();}
    }
}
