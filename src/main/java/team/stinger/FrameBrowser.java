package team.stinger;

import javax.swing.*;
import java.awt.image.*;
import java.awt.event.*;
import java.awt.*;
import java.util.*;
import java.io.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import javax.swing.filechooser.*;

/**
 * Frame Browser
 */
public class FrameBrowser extends JFrame {
    private JLabel nowShowing;
    private JPanel buttonPanel;
    private JButton forwardButton;
    private JButton backwardButton;
    private JButton saveButton;
    private DPanel dp;
    private JScrollPane scrollPane;

    private BufferedImage bi = null;
    private java.util.List<BufferedImage> frames = null;
    private int fnum = 0;
    private int numframes = 0;

    private JPanel thumbnailsPanel;
    private JScrollPane thumbnailsScrollPane;

    /**
     * Creates a new frame browser
     * @param b The image to view
     * @param f The file of the image
     */
    public FrameBrowser(BufferedImage b, File f) {
        BufferedImage bnext;
        bi = b;
        initComponents();
        fnum = 0;
        numframes = 0;
        frames = new ArrayList<>();
        try {
            ImageInputStream ii = ImageIO.createImageInputStream(f);
            if (ii == null) System.out.println("Couldn't create input stream");
            ImageReader rr = ImageIO.getImageReaders(ii).next();
            if (rr == null) System.out.println("No image reader");
            rr.setInput(ii);
            int fread = rr.getMinIndex();
            while (true) {
                bnext = rr.read(numframes + fread);
                if (bnext == null) break;
                frames.add(bnext);
                addThumbnail(bnext, numframes);  // 添加缩略图
                numframes++;
            }
            System.out.println("总帧数 " + numframes);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to load file: " + e.toString());
        } catch (IndexOutOfBoundsException e) {
            // expected for reading too many frames
        }
        newImage();
    }

    // <editor-fold defaultstate="collapsed" desc="Initcomponents()">
    private void initComponents() {
        nowShowing = new JLabel();
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        this.add(nowShowing, BorderLayout.NORTH);

        buttonPanel = new JPanel();
        backwardButton = new JButton("<");
        backwardButton.addActionListener(this::backwardButtonActionPerformed);
        forwardButton = new JButton(">");
        forwardButton.addActionListener(this::forwardButtonActionPerformed);
        saveButton = new JButton("Save");
        saveButton.addActionListener(this::saveButtonActionPerformed);
        buttonPanel.add(backwardButton);
        buttonPanel.add(forwardButton);
        buttonPanel.add(saveButton);
        buttonPanel.add(nowShowing);
        add(buttonPanel, BorderLayout.SOUTH);

        dp = new DPanel();
        scrollPane = new JScrollPane(dp);
        add(scrollPane, BorderLayout.CENTER);

        // 缩略图面板
        thumbnailsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        thumbnailsScrollPane = new JScrollPane(thumbnailsPanel, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        add(thumbnailsScrollPane, BorderLayout.NORTH);

        pack();
        setSize(800, 600);
    }

    // 为每个帧生成缩略图并添加到缩略图面板
    private void addThumbnail(BufferedImage frame, int index) {
        ImageIcon thumbnail = new ImageIcon(frame.getScaledInstance(100, 100, Image.SCALE_SMOOTH));
        JButton thumbnailButton = new JButton(thumbnail);
        thumbnailButton.addActionListener(e -> showFrame(index));
        thumbnailsPanel.add(thumbnailButton);
    }

    // 点击缩略图时显示对应的帧
    private void showFrame(int index) {
        fnum = index;
        newImage();
    }

    private void backwardButtonActionPerformed(ActionEvent evt) {
        fnum--;
        if (fnum < 0) fnum = numframes - 1;
        newImage();
    }

    private void forwardButtonActionPerformed(ActionEvent evt) {
        fnum++;
        if (fnum >= numframes) fnum = 0;
        newImage();
    }

    private void saveButtonActionPerformed(ActionEvent evt) {
        File sfile;
        JFileChooser fileChooser = new JFileChooser(System.getProperty("user.dir"));
        FileNameExtensionFilter filter = new FileNameExtensionFilter("Images", "jpg", "gif", "png", "bmp");
        fileChooser.setFileFilter(filter);
        fileChooser.setSelectedFile(new File("frame" + (fnum + 1) + ".bmp"));
        int rVal = fileChooser.showSaveDialog(this);
        System.setProperty("user.dir", fileChooser.getCurrentDirectory().getAbsolutePath());
        if (rVal == JFileChooser.APPROVE_OPTION) {
            sfile = fileChooser.getSelectedFile();
            try {
                BufferedImage bbx = frames.get(fnum);
                int rns = sfile.getName().lastIndexOf(".") + 1;
                if (rns == 0)
                    ImageIO.write(bbx, "bmp", sfile);
                else
                    ImageIO.write(bbx, sfile.getName().substring(rns), sfile);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Failed to write file: " + e.toString());
            }
        }
    }

    private void newImage() {
        nowShowing.setText("Frame: " + (fnum + 1) + " of " + numframes);  // 显示总帧数
        if (numframes == 0) return;
        dp.setImage(frames.get(fnum));
        dp.setSize(frames.get(fnum).getWidth(), frames.get(fnum).getHeight());
        dp.setPreferredSize(new Dimension(frames.get(fnum).getWidth(), frames.get(fnum).getHeight()));
        pack();
        dp.apply(100);
        scrollPane.revalidate();
        repaint();
        this.setSize(1000,750);
    }

    public static void main(String[] args) {
        // 示例代码，替换为你的GIF文件路径
        File file = new File("path_to_your_gif.gif");
        BufferedImage bi = null;
        // 加载图像...
        new FrameBrowser(bi, file).setVisible(true);
    }
}
