package team.stinger;

import java.awt.AWTException;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 全屏显示的窗口, 按 Alt + F4 退出
 * @author pengranxiang
 */
public class QRcodeSelect extends JFrame {
    private static final long serialVersionUID = -3758062802950480258L;

    private Image image;
    private JLabel imageLabel;
    private JPanel overlayPanel;

    private int x, y, xEnd, yEnd;   // 用于记录鼠标点击开始和结束的坐标
    private Rectangle rect;         // 用于保存红色矩形框的位置和大小
    private BufferedImage selectedImage;  // 用于保存最终截图结果

    private ScreenshotListener listener;  // 接收截图的监听器

    public QRcodeSelect(ScreenshotListener listener) throws AWTException, InterruptedException {
        this.listener = listener;

        // 取得屏幕尺寸
        Dimension screenDims = Toolkit.getDefaultToolkit().getScreenSize();
        // 取得全屏幕截图
        image = GraphicsUtils.getScreenImage(0, 0, screenDims.width, screenDims.height);
        // 用于展示截图
        imageLabel = new JLabel(new ImageIcon(image));
        // 当鼠标在imageLabel上时，展示为十字形
        this.setCursor(new Cursor(Cursor.CROSSHAIR_CURSOR));

        createAction();

        this.getContentPane().add(imageLabel);

        // Overlay panel to draw red rectangle
        overlayPanel = new JPanel() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (rect != null) {
                    g.setColor(java.awt.Color.RED);
                    g.drawRect(rect.x, rect.y, rect.width, rect.height);
                }
            }
        };
        overlayPanel.setOpaque(false);
        this.getLayeredPane().add(overlayPanel, Integer.valueOf(Integer.MAX_VALUE));

        this.setUndecorated(true);  // 去掉窗口装饰
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setVisible(true);
        this.setExtendedState(JFrame.MAXIMIZED_BOTH);  // 窗口最大化
        overlayPanel.setBounds(0, 0, screenDims.width, screenDims.height);
    }

    /**
     * 实现监听动作
     */
    private void createAction() {
        imageLabel.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                x = e.getX();
                y = e.getY();
            }

            public void mouseReleased(MouseEvent e) {
                xEnd = e.getX();
                yEnd = e.getY();

                // 鼠标弹起时，取得鼠标起始两点组成的矩形区域的图像
                try {
                    selectedImage = GraphicsUtils.getScreenImage(Math.min(x, xEnd), Math.min(y, yEnd), Math.abs(xEnd - x), Math.abs(yEnd - y));

                    // 将截图结果传递给监听器
                    if (listener != null) {
                        listener.onScreenshotCaptured(selectedImage);
                    }

                    // 关闭窗口
                    dispose();
                } catch (AWTException | InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        });

        imageLabel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                xEnd = e.getX();
                yEnd = e.getY();
                // 计算矩形框的位置和大小
                rect = new Rectangle(Math.min(x, xEnd), Math.min(y, yEnd), Math.abs(xEnd - x), Math.abs(yEnd - y));
                overlayPanel.repaint();  // 更新矩形框
            }
        });
    }

    public static void scanQRcode() throws AWTException, InterruptedException {
        // 使用回调函数来获取截图结果
        new QRcodeSelect(new ScreenshotListener() {
            @Override
            public void onScreenshotCaptured(BufferedImage screenshot) {
//                System.out.println("截图已完成，处理截图...");
                new QRcodeDecode(screenshot, null).setVisible(true);
                // 在这里可以对截图进行保存或其他处理
            }
        });
    }
}

/**
 * 截图功能的工具类
 */
class GraphicsUtils {
    /**
     * 截图屏幕中制定区域的图片
     * @param x
     * @param y
     * @param w
     * @param h
     * @return 被截部分的BufferedImage对象
     * @throws AWTException
     * @throws InterruptedException
     */
    public static BufferedImage getScreenImage(int x, int y, int w, int h) throws AWTException, InterruptedException {
        Robot robot = new Robot();
        BufferedImage screen = robot.createScreenCapture(new Rectangle(x, y, w, h));
        return screen;
    }
}

/**
 * 截图完成后的回调接口
 */
interface ScreenshotListener {
    void onScreenshotCaptured(BufferedImage screenshot);
}
