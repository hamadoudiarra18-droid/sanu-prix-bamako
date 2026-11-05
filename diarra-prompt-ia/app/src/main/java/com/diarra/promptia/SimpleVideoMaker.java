package com.diarra.promptia;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Montage local MP4 pour les clips de même format Wan/FLUX déjà importés.
 *  AUCUNE connexion au réseau et AUCUN appel facturable n'est effectué ici.
 */
final class SimpleVideoMaker {
    interface Progress { void update(String message, int percent); }
    private static final int AUDIO_RATE = 44100;
    private static final long LIMIT_US = 180_000_000L; // 3 minutes maximum
    private static final long MAX_AUDIO_BYTES = 75_000_000L;
    private SimpleVideoMaker() {}

    private static void progress(Progress cb,String message,int value){if(cb!=null)cb.update(message,value);}

    static void create(Context context,List<File> clips,File voice,File output,Progress cb) throws Exception {
        if(clips.isEmpty())throw new IOException("Ajoutez d'abord une vidéo MP4.");
        if(clips.size()>24)throw new IOException("Maximum 24 vidéos dans un montage.");
        if(output.getParentFile()!=null&&!output.getParentFile().exists())output.getParentFile().mkdirs();
        File audioTemp = new File(context.getCacheDir(),"diarra_audio_"+System.currentTimeMillis()+".m4a");
        try {
            long totalUs=totalDuration(clips);
            if(totalUs>LIMIT_US)throw new IOException("Montage trop long : maximum 3 minutes.");
            progress(cb,"Préparation de la bande sonore…",10);
            synthesizeAudio(audioTemp,totalUs,voice,clips.size(),cb);
            progress(cb,"Assemblage des vidéos…",42);
            combineClips(clips,audioTemp,output,cb);
            progress(cb,"Vidéo terminée.",96);
        }catch(Exception ex){if(output.exists())output.delete();throw ex;}
        finally{if(audioTemp.exists())audioTemp.delete();}
    }

    static long totalDuration(List<File> clips) throws Exception {
        long sum=0;
        for(File file:clips){
            if(!file.isFile()||file.length()<16)throw new IOException("Une vidéo manque sur le téléphone.");
            MediaExtractor extractor=new MediaExtractor();
            try {
                extractor.setDataSource(file.getAbsolutePath());
                int track=videoTrack(extractor);if(track<0)throw new IOException("Un MP4 ne contient pas de piste vidéo.");
                MediaFormat format=extractor.getTrackFormat(track);
                long dur=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):0;
                if(dur<=0)dur=5_000_000L;
                sum+=dur;
            }finally{extractor.release();}
        }
        return sum;
    }

    private static int videoTrack(MediaExtractor e){
        for(int i=0;i<e.getTrackCount();i++)if(e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/"))return i;
        return -1;
    }
    private static int audioTrack(MediaExtractor e){
        for(int i=0;i<e.getTrackCount();i++)if(e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/"))return i;
        return -1;
    }
    private static ByteBuffer inputBuffer(int size){return ByteBuffer.allocateDirect(Math.max(size,1048576));}

    private static void combineClips(List<File> clips,File music,File output,Progress cb) throws Exception {
        MediaExtractor first=new MediaExtractor(),sound=new MediaExtractor();
        MediaMuxer muxer=null;boolean started=false;
        try{
            first.setDataSource(clips.get(0).getAbsolutePath());int firstVideo=videoTrack(first);
            if(firstVideo<0)throw new IOException("Première vidéo invalide.");
            MediaFormat videoFormat=first.getTrackFormat(firstVideo);
            String expectedMime=videoFormat.getString(MediaFormat.KEY_MIME);
            int width=videoFormat.getInteger(MediaFormat.KEY_WIDTH),height=videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
            sound.setDataSource(music.getAbsolutePath());int audioIndex=audioTrack(sound);
            if(audioIndex<0)throw new IOException("Bande sonore impossible à assembler.");
            MediaFormat audioFormat=sound.getTrackFormat(audioIndex);
            muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoDest=muxer.addTrack(videoFormat);
            int audioDest=muxer.addTrack(audioFormat);
            muxer.start();started=true;
            long nextUs=0,previousUs=-1;
            ByteBuffer b=inputBuffer(4*1024*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            for(int c=0;c<clips.size();c++){
                MediaExtractor e=new MediaExtractor();
                try{
                    e.setDataSource(clips.get(c).getAbsolutePath());
                    int track=videoTrack(e);if(track<0)throw new IOException("Une vidéo est invalide.");
                    MediaFormat f=e.getTrackFormat(track);
                    if(!expectedMime.equals(f.getString(MediaFormat.KEY_MIME)) || width!=f.getInteger(MediaFormat.KEY_WIDTH)
                       || height!=f.getInteger(MediaFormat.KEY_HEIGHT) || !sameCodecData(videoFormat,f))
                        throw new IOException("Les MP4 n'ont pas tous le même format vidéo. Réimportez des vidéos produites avec le même modèle et la même résolution.");
                    e.selectTrack(track);
                    long startPts=-1,lastLocal=-1,frameUs=33333;
                    if(f.containsKey(MediaFormat.KEY_FRAME_RATE)){
                        int rate=f.getInteger(MediaFormat.KEY_FRAME_RATE);
                        if(rate>0&&rate<=120)frameUs=1000000L/rate;
                    }
                    while(true){
                        long pts=e.getSampleTime();if(pts<0)break;
                        int needed=(int)Math.max(1,e.getSampleSize());
                        if(needed>32*1024*1024)throw new IOException("Trame vidéo trop volumineuse.");
                        if(needed>b.capacity())b=inputBuffer(needed+65536);
                        b.clear();int n=e.readSampleData(b,0);if(n<0)break;
                        if(startPts<0)startPts=pts;
                        long local=Math.max(0,pts-startPts);
                        long finalPts=Math.max(previousUs+1,nextUs+local);
                        info.set(0,n,finalPts,e.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC);
                        muxer.writeSampleData(videoDest,b,info);
                        previousUs=finalPts;lastLocal=local;
                        e.advance();
                    }
                    if(lastLocal<0)throw new IOException("Une des vidéos est vide.");
                    nextUs=previousUs+frameUs;
                }finally{e.release();}
                progress(cb,"Montage : vidéo "+(c+1)+" sur "+clips.size(),42+(int)(40.0*(c+1)/clips.size()));
            }
            sound.selectTrack(audioIndex);ByteBuffer a=inputBuffer(512*1024);
            while(true){
                long pts=sound.getSampleTime();if(pts<0)break;
                int needed=(int)Math.max(1,sound.getSampleSize());
                if(needed>a.capacity())a=inputBuffer(needed+32768);
                a.clear();int n=sound.readSampleData(a,0);if(n<0)break;
                info.set(0,n,pts,sound.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC);
                muxer.writeSampleData(audioDest,a,info);sound.advance();
            }
            muxer.stop();started=false;
        }finally{
            first.release();sound.release();
            if(muxer!=null){if(started){try{muxer.stop();}catch(Exception ignored){}}muxer.release();}
        }
    }

    private static boolean sameCodecData(MediaFormat a,MediaFormat b){
        for(String key:new String[]{"csd-0","csd-1","csd-2"}){
            boolean aa=a.containsKey(key),bb=b.containsKey(key);if(aa!=bb)return false;
            if(aa){ByteBuffer x=a.getByteBuffer(key),y=b.getByteBuffer(key);
                if(x==null||y==null)return false;
                x=x.duplicate();y=y.duplicate();
                if(x.remaining()!=y.remaining())return false;
                while(x.hasRemaining())if(x.get()!=y.get())return false;
            }
        }
        return true;
    }

    private static void synthesizeAudio(File output,long durationUs,File voice,int count,Progress cb) throws Exception {
        int length=(int)Math.ceil(durationUs*AUDIO_RATE/1000000d);
        if(length<=0||length>AUDIO_RATE*181)throw new IOException("Durée audio non prise en charge.");
        float[] mix=new float[length];
        // Petit fond instrumental original, volontairement discret sous la voix.
        double[] roots={220.0,196.0,174.61,196.0};
        final double twopi=2*Math.PI;
        for(int i=0;i<length;i++){
            double s=i/(double)AUDIO_RATE;
            int bar=(int)(s/4.0);double within=s%4;
            double v=roots[bar%roots.length];
            double env=Math.min(1,Math.min(within/0.35,(4-within)/0.6));env=Math.max(0,env);
            double pad=(Math.sin(twopi*(v/2)*s)+0.48*Math.sin(twopi*(v*1.5)*s)+0.17*Math.sin(twopi*(v*2)*s));
            double pluck=Math.pow(Math.max(0,1-(s%0.75)/0.75),3)*Math.sin(twopi*v*2*s);
            mix[i]=(float)(0.037*env*pad+0.022*pluck);
        }
        // Bruitage de transition (whoosh léger) à chaque changement de vidéo.
        Random r=new Random(7851);
        for(int k=1;k<count;k++){
            int boundary=(int)((long)length*k/count);
            int width=(int)(.24*AUDIO_RATE);
            for(int j=0;j<width;j++){
                int pos=boundary-width/2+j;if(pos<0||pos>=mix.length)continue;
                double phase=j/(double)width;double curve=Math.sin(Math.PI*phase);
                mix[pos]+=(float)((r.nextDouble()*2-1)*0.11*curve);
            }
        }
        if(voice!=null){
            progress(cb,"Ajout de votre voix off…",18);
            Pcm pcm=decode(voice);
            for(int i=0;i<length;i++){
                double sourceIndex=(double)i*pcm.rate/AUDIO_RATE;
                int a=(int)sourceIndex;if(a>=pcm.samples.length)break;
                int b=Math.min(a+1,pcm.samples.length-1);
                float t=(float)(sourceIndex-a);
                mix[i]+=0.91f*(pcm.samples[a]*(1-t)+pcm.samples[b]*t);
            }
        }
        progress(cb,"Création du son et des bruitages…",29);
        encodeAAC(output,mix);
    }

    private static final class Pcm {
        final int rate;final float[] samples;
        Pcm(int r,float[] s){rate=r;samples=s;}
    }

    private static Pcm decode(File file)throws Exception{
        if(!file.isFile())throw new IOException("Voix off introuvable.");
        if(file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav")){
            try{return wavPcm(file);}catch(Exception ex){ /* essayer décodeur système */ }
        }
        MediaExtractor ex=new MediaExtractor();MediaCodec decoder=null;
        try{
            ex.setDataSource(file.getAbsolutePath());int track=audioTrack(ex);
            if(track<0)throw new IOException("Le fichier ne contient pas de son lisible.");
            MediaFormat in=ex.getTrackFormat(track);String mime=in.getString(MediaFormat.KEY_MIME);
            ex.selectTrack(track);decoder=MediaCodec.createDecoderByType(mime);decoder.configure(in,null,null,0);decoder.start();
            ByteArrayOutputStream pcmOut=new ByteArrayOutputStream();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            int sampleRate=in.containsKey(MediaFormat.KEY_SAMPLE_RATE)?in.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
            int channels=in.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?in.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
            int encoding=2;
            boolean inputEnded=false,finished=false;int idle=0;
            while(!finished && idle<2000){
                if(!inputEnded){int idx=decoder.dequeueInputBuffer(10000);
                    if(idx>=0){ByteBuffer buf=decoder.getInputBuffer(idx);if(buf==null)throw new IOException("Décodeur audio inaccessible");buf.clear();
                        int n=ex.readSampleData(buf,0);
                        if(n<0){decoder.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true;}
                        else{decoder.queueInputBuffer(idx,0,n,Math.max(0,ex.getSampleTime()),0);ex.advance();}
                    }
                }
                int idx=decoder.dequeueOutputBuffer(info,10000);
                if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat f=decoder.getOutputFormat();if(f.containsKey(MediaFormat.KEY_SAMPLE_RATE))sampleRate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if(f.containsKey(MediaFormat.KEY_CHANNEL_COUNT))channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if(f.containsKey("pcm-encoding"))encoding=f.getInteger("pcm-encoding");
                }else if(idx>=0){
                    if(info.size>0){ByteBuffer out=decoder.getOutputBuffer(idx);if(out==null)throw new IOException("Sortie audio indisponible");
                        out.position(info.offset);out.limit(info.offset+info.size);
                        byte[] bytes=new byte[info.size];out.get(bytes);
                        if(pcmOut.size()+bytes.length>MAX_AUDIO_BYTES)throw new IOException("Voix trop longue (maximum 3 minutes).");
                        pcmOut.write(bytes);
                    }
                    finished=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    decoder.releaseOutputBuffer(idx,false);idle=0;
                }else idle++;
            }
            if(idle>=2000)throw new IOException("Décodage de la voix trop long.");
            if(channels<1||channels>8||sampleRate<8000||sampleRate>192000)throw new IOException("Format de voix non pris en charge.");
            byte[] bytes=pcmOut.toByteArray();ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int bytesPer=encoding==4?4:2;
            if(encoding!=4&&encoding!=2)throw new IOException("Format PCM de voix non pris en charge.");
            int frames=bytes.length/(channels*bytesPer);float[] samples=new float[frames];
            for(int f=0;f<frames;f++){
                float sum=0;for(int c=0;c<channels;c++)sum+=encoding==4?b.getFloat():b.getShort()/32768f;
                samples[f]=sum/channels;
            }
            return new Pcm(sampleRate,samples);
        }finally{if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}ex.release();}
    }

    // WAV PCM 16-bit sans dépendance externe.
    private static Pcm wavPcm(File f)throws Exception{
        try(java.io.RandomAccessFile wav=new java.io.RandomAccessFile(f,"r")){
            if(readAscii(wav,4).equals("RIFF")){wav.skipBytes(4);}else throw new IOException("Pas RIFF");
            if(!readAscii(wav,4).equals("WAVE"))throw new IOException("Pas WAV");
            int rate=0,ch=0,bits=0,format=0;byte[] audio=null;
            while(wav.getFilePointer()+8<=wav.length()){
                String name=readAscii(wav,4);long size=readLE32(wav);long start=wav.getFilePointer();
                if(size<0||size>MAX_AUDIO_BYTES||start+size>wav.length())throw new IOException("WAV invalide");
                if(name.equals("fmt ")){format=readLE16(wav);ch=readLE16(wav);rate=(int)readLE32(wav);wav.skipBytes(6);bits=readLE16(wav);}
                if(name.equals("data")){audio=new byte[(int)size];wav.readFully(audio);}
                wav.seek(start+size+(size%2));
                if(audio!=null&&rate>0)break;
            }
            if(format!=1||bits!=16||ch<1||ch>2||rate<8000||audio==null)throw new IOException("WAV PCM 16 bits nécessaire");
            ByteBuffer b=ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);int frames=audio.length/(ch*2);float[] samples=new float[frames];
            for(int i=0;i<frames;i++){float v=0;for(int c=0;c<ch;c++)v+=b.getShort()/32768f;samples[i]=v/ch;}
            return new Pcm(rate,samples);
        }
    }
    private static String readAscii(java.io.RandomAccessFile f,int n)throws IOException{byte[] b=new byte[n];f.readFully(b);return new String(b,java.nio.charset.StandardCharsets.US_ASCII);}
    private static int readLE16(java.io.RandomAccessFile f)throws IOException{return f.readUnsignedByte()|(f.readUnsignedByte()<<8);}
    private static long readLE32(java.io.RandomAccessFile f)throws IOException{return (long)f.readUnsignedByte()|((long)f.readUnsignedByte()<<8)|((long)f.readUnsignedByte()<<16)|((long)f.readUnsignedByte()<<24);}

    private static void encodeAAC(File file,float[] audio)throws Exception{
        MediaCodec encoder=null;MediaMuxer muxer=null;boolean started=false;
        try{
            MediaFormat f=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,AUDIO_RATE,1);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE,96000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384);
            encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();
            muxer=new MediaMuxer(file.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int track=-1;int sample=0;boolean inputEnded=false,outputEnded=false;int idle=0;
            while(!outputEnded && idle<2000){
                if(!inputEnded){int idx=encoder.dequeueInputBuffer(10000);
                    if(idx>=0){ByteBuffer b=encoder.getInputBuffer(idx);if(b==null)throw new IOException("Encodeur indisponible");
                        b.clear();b.order(ByteOrder.LITTLE_ENDIAN);
                        int n=Math.min(audio.length-sample,b.remaining()/2);
                        long pts=sample*1_000_000L/AUDIO_RATE;
                        for(int i=0;i<n;i++){float a=Math.max(-0.96f,Math.min(.96f,audio[sample+i]));b.putShort((short)(a*32767));}
                        int flags=(sample+n>=audio.length)?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0;
                        encoder.queueInputBuffer(idx,0,n*2,pts,flags);sample+=n;
                        if(flags!=0)inputEnded=true;
                    }
                }
                int idx=encoder.dequeueOutputBuffer(info,10000);
                if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=muxer.addTrack(encoder.getOutputFormat());muxer.start();started=true;}
                else if(idx>=0){
                    if(info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                        if(!started)throw new IOException("Encodeur audio non initialisé");
                        ByteBuffer encoded=encoder.getOutputBuffer(idx);
                        if(encoded==null)throw new IOException("Audio encodé inaccessible");
                        encoded.position(info.offset);encoded.limit(info.offset+info.size);
                        muxer.writeSampleData(track,encoded,info);
                    }
                    outputEnded=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    encoder.releaseOutputBuffer(idx,false);idle=0;
                }else idle++;
            }
            if(idle>=2000)throw new IOException("Encodage audio trop long.");
            if(started){muxer.stop();started=false;}
        }finally{
            if(encoder!=null){try{encoder.stop();}catch(Exception ignored){}encoder.release();}
            if(muxer!=null){if(started){try{muxer.stop();}catch(Exception ignored){}}muxer.release();}
        }
    }

    static Uri exportDownloads(Context context,File f,String name)throws Exception{
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/DiarraPromptIA");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        ContentResolver resolver=context.getContentResolver();
        Uri u=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(u==null)throw new IOException("Impossible d'enregistrer dans Téléchargements");
        try{
            try(InputStream in=new FileInputStream(f);OutputStream out=resolver.openOutputStream(u)){
                if(out==null)throw new IOException("Mémoire indisponible");
                byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);
            resolver.update(u,done,null,null);
        }catch(Exception ex){resolver.delete(u,null,null);throw ex;}
        return u;
    }
}
