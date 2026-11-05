import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Builds the SUD Time Fixer icon: the WhalePi Host artwork with a settings cog
 * (holding a clock face) badge in the bottom right corner.
 *
 *   java build/icon/MakeIcon.java
 *
 * Writes src/main/resources/sudfixer/sudfixer_icon.png, src/main/deploy/sudfixer.ico
 * and (on macOS, through iconutil) src/main/deploy/sudfixer.icns.
 */
public class MakeIcon {

    static final Color BADGE_BG = new Color(0x12, 0x12, 0x18);
    static final Color RING = new Color(0x5D, 0xAD, 0xE2);
    static final Color COG = new Color(0xF4, 0xD0, 0x3F);
    static final Color FACE = new Color(0x1E, 0x1E, 0x26);

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        BufferedImage base = ImageIO.read(root.resolve("build/icon/whalepi_icon.png").toFile());

        BufferedImage master = render(base, 1024);
        Path png = root.resolve("src/main/resources/sudfixer/sudfixer_icon.png");
        Files.createDirectories(png.getParent());
        ImageIO.write(scale(master, 512), "png", png.toFile());

        Path deploy = root.resolve("src/main/deploy");
        Files.createDirectories(deploy);
        writeIco(master, new int[] {16, 24, 32, 48, 64, 128, 256}, deploy.resolve("sudfixer.ico"));

        Path iconset = Files.createTempDirectory("sudfixer").resolve("sudfixer.iconset");
        Files.createDirectories(iconset);
        for (int s : new int[] {16, 32, 128, 256, 512}) {
            ImageIO.write(scale(master, s), "png", iconset.resolve("icon_" + s + "x" + s + ".png").toFile());
            ImageIO.write(scale(master, s * 2), "png", iconset.resolve("icon_" + s + "x" + s + "@2x.png").toFile());
        }
        try {
            Process p = new ProcessBuilder("iconutil", "-c", "icns", iconset.toString(),
                    "-o", deploy.resolve("sudfixer.icns").toString()).inheritIO().start();
            System.out.println(p.waitFor() == 0 ? "wrote sudfixer.icns" : "iconutil failed");
        } catch (IOException e) {
            System.out.println("iconutil not available (macOS only): sudfixer.icns not rebuilt");
        }
        System.out.println("done");
    }

    static BufferedImage render(BufferedImage base, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.drawImage(base, 0, 0, size, size, null);

        // Badge: centred toward the bottom-right corner, over the empty sea.
        double r = size * 0.215;
        double cx = size * 0.735, cy = size * 0.735;

        // soft shadow
        for (int i = 6; i > 0; i--) {
            g.setColor(new Color(0, 0, 0, 14));
            double rr = r + size * 0.006 * i;
            g.fill(new Ellipse2D.Double(cx - rr, cy - rr + size * 0.008, rr * 2, rr * 2));
        }
        g.setColor(BADGE_BG);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        g.setColor(RING);
        g.setStroke(new BasicStroke((float) (size * 0.014)));
        double ri = r - size * 0.007;
        g.draw(new Ellipse2D.Double(cx - ri, cy - ri, ri * 2, ri * 2));

        // Cog
        double outer = r * 0.80, inner = r * 0.63, hole = r * 0.44;
        Area cog = new Area(new Ellipse2D.Double(cx - inner, cy - inner, inner * 2, inner * 2));
        int teeth = 8;
        for (int i = 0; i < teeth; i++) {
            double a = Math.PI * 2 * i / teeth;
            double tw = r * 0.27;
            Shape tooth = new RoundRectangle2D.Double(-tw / 2, -outer, tw, outer - inner * 0.5, tw * 0.35, tw * 0.35);
            AffineTransform at = new AffineTransform();
            at.translate(cx, cy);
            at.rotate(a);
            cog.add(new Area(at.createTransformedShape(tooth)));
        }
        cog.subtract(new Area(new Ellipse2D.Double(cx - hole, cy - hole, hole * 2, hole * 2)));
        g.setColor(COG);
        g.fill(cog);

        // Clock face inside the cog
        g.setColor(FACE);
        g.fill(new Ellipse2D.Double(cx - hole, cy - hole, hole * 2, hole * 2));
        g.setColor(COG);
        g.setStroke(new BasicStroke((float) (r * 0.085), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // hour hand to ~10 o'clock, minute hand to 12
        double hl = hole * 0.52, ml = hole * 0.74;
        double ha = Math.toRadians(-60);
        g.draw(new Line2D.Double(cx, cy, cx + hl * Math.sin(ha), cy - hl * Math.cos(ha)));
        g.draw(new Line2D.Double(cx, cy, cx, cy - ml));
        double dot = r * 0.07;
        g.fill(new Ellipse2D.Double(cx - dot, cy - dot, dot * 2, dot * 2));

        g.dispose();
        return img;
    }

    static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage cur = src;
        int w = src.getWidth();
        // step down by halves for smooth small icons
        while (w / 2 >= size) {
            w /= 2;
            cur = resize(cur, w);
        }
        return w == size ? cur : resize(cur, size);
    }

    static BufferedImage resize(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        hints(g);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** An ICO holding PNG images, which every Windows since Vista reads. */
    static void writeIco(BufferedImage master, int[] sizes, Path out) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(scale(master, s), "png", b);
            pngs.add(b.toByteArray());
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        le16(o, 0); le16(o, 1); le16(o, sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            o.write(s >= 256 ? 0 : s);
            o.write(s >= 256 ? 0 : s);
            o.write(0); o.write(0);
            le16(o, 1); le16(o, 32);
            le32(o, pngs.get(i).length);
            le32(o, offset);
            offset += pngs.get(i).length;
        }
        for (byte[] p : pngs) o.write(p);
        Files.write(out, o.toByteArray());
    }

    static void le16(OutputStream o, int v) throws IOException { o.write(v); o.write(v >> 8); }
    static void le32(OutputStream o, int v) throws IOException { le16(o, v); le16(o, v >> 16); }
}
