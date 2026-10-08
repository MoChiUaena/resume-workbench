package dev.localresume;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** Small, in-memory PDF image choices; never persists an unconfirmed import. */
final class PdfImageCandidates {
    static final int MAX_CHOICES=8, MAX_ATTEMPTS=32, MAX_PIXELS=4_000_000, MAX_DECODE_PIXELS=12_000_000,
        MAX_EDGE=640, MAX_BYTES=524_288, MAX_TOTAL_BYTES=2_097_152;
    private PdfImageCandidates() {}

    static List<PdfReader.ImageCandidate> extract(PDDocument document) {
        ImageIO.setUseCache(false);
        var choices=new ArrayList<PdfReader.ImageCandidate>();var seen=new HashSet<String>();int total=0,attempts=0;long decodedPixels=0;
        pages: for(int pageIndex=0;pageIndex<document.getNumberOfPages()&&choices.size()<MAX_CHOICES;pageIndex++) {
            var resources=document.getPage(pageIndex).getResources();if(resources==null)continue;
            int imageIndex=0;
            for(var name:resources.getXObjectNames()) {
                try {
                    if(!resources.isImageXObject(name))continue;
                    imageIndex++;
                    if(++attempts>MAX_ATTEMPTS)break pages;
                    var image=(PDImageXObject)resources.getXObject(name);
                    int width=image.getWidth(),height=image.getHeight();
                    if(width<16||height<16||(long)width*height>MAX_PIXELS)continue;
                    int edge=Math.max(width,height);double scale=Math.min(1d,(double)MAX_EDGE/edge);
                    int scaledWidth=Math.max(1,(int)Math.round(width*scale)),scaledHeight=Math.max(1,(int)Math.round(height*scale));
                    if(scaledWidth<16||scaledHeight<16)continue;
                    if(decodedPixels+(long)width*height>MAX_DECODE_PIXELS)break pages;
                    decodedPixels+=(long)width*height;
                    var decoded=image.getImage();
                    if(decoded==null||decoded.getWidth()!=width||decoded.getHeight()!=height)continue;
                    boolean alpha=decoded.getColorModel().hasAlpha();
                    var bounded=new BufferedImage(scaledWidth,scaledHeight,alpha?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics=bounded.createGraphics();
                    try {graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(decoded,0,0,scaledWidth,scaledHeight,null);}finally{graphics.dispose();}
                    String format=alpha?"png":"jpeg";
                    var output=new ByteArrayOutputStream();
                    if(!ImageIO.write(bounded,format,output))continue;
                    byte[] bytes=output.toByteArray();
                    if(bytes.length>MAX_BYTES||total+bytes.length>MAX_TOTAL_BYTES||!seen.add(ImageService.sha(bytes)))continue;
                    total+=bytes.length;
                    choices.add(new PdfReader.ImageCandidate("p"+(pageIndex+1)+"-i"+imageIndex,pageIndex+1,scaledWidth,scaledHeight,
                        "image/"+format,Base64.getEncoder().encodeToString(bytes)));
                    if(choices.size()==MAX_CHOICES)break;
                } catch(IOException|RuntimeException ignored) { /* A bad picture must not discard readable resume text. */ }
            }
        }
        return List.copyOf(choices);
    }
}
