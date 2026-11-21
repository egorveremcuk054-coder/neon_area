package arena.client;

import arena.common.Config;
import arena.server.GameServer;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

/** Стартовое окно: ввести имя и выбрать — создать игру или подключиться к другу. */
public final class Launcher {
    private Launcher() {}

    /** Вызывать из потока Swing (EDT). */
    public static void open() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // останется стандартный вид Swing — не страшно
        }

        JFrame frame = new JFrame("Неоновая арена");
        JTextField nameField = new JTextField("Игрок" + ThreadLocalRandom.current().nextInt(100, 1000), 16);
        JRadioButton hostButton = new JRadioButton("Создать игру", true);
        JRadioButton joinButton = new JRadioButton("Подключиться к другу");
        ButtonGroup mode = new ButtonGroup();
        mode.add(hostButton);
        mode.add(joinButton);
        JSpinner botsSpinner = new JSpinner(new SpinnerNumberModel(1, 0, Config.MAX_PLAYERS - 1, 1));
        JTextField addressField = new JTextField("127.0.0.1", 16);
        JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(Config.DEFAULT_PORT, 1024, 65535, 1));
        portSpinner.setEditor(new JSpinner.NumberEditor(portSpinner, "#")); // без пробела «7 777»
        JButton playButton = new JButton("Играть");
        JLabel status = new JLabel(" ");

        Runnable updateEnabled = () -> {
            boolean host = hostButton.isSelected();
            botsSpinner.setEnabled(host);
            addressField.setEnabled(!host);
        };
        hostButton.addActionListener(e -> updateEnabled.run());
        joinButton.addActionListener(e -> updateEnabled.run());
        updateEnabled.run();

        JLabel title = new JLabel("НЕОНОВАЯ АРЕНА");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JLabel hint = new JLabel("<html><small>Боты освобождают место, когда заходят люди.<br>"
                + "Адрес для друга появится в верхней панели игры.</small></html>");
        hint.setForeground(Color.GRAY);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        addRow(form, c, row++, title, null);
        addRow(form, c, row++, new JLabel("Ваше имя:"), nameField);
        addRow(form, c, row++, hostButton, null);
        addRow(form, c, row++, new JLabel("      Ботов:"), botsSpinner);
        addRow(form, c, row++, joinButton, null);
        addRow(form, c, row++, new JLabel("      Адрес друга:"), addressField);
        addRow(form, c, row++, new JLabel("Порт:"), portSpinner);
        addRow(form, c, row++, hint, null);
        addRow(form, c, row++, playButton, null);
        addRow(form, c, row, status, null);

        playButton.addActionListener(e -> {
            playButton.setEnabled(false);
            status.setForeground(Color.DARK_GRAY);
            status.setText("Подключение...");
            String name = nameField.getText();
            boolean host = hostButton.isSelected();
            String address = host ? "127.0.0.1" : addressField.getText().strip();
            int port = (Integer) portSpinner.getValue();
            int bots = (Integer) botsSpinner.getValue();

            // Подключение может занять несколько секунд — делаем его не в потоке Swing, чтобы окно не зависло.
            Thread connector = new Thread(() -> {
                GameServer server = null;
                try {
                    String hostInfo = null;
                    if (host) {
                        server = new GameServer(port, bots);
                        server.start();
                        hostInfo = "Адрес для друга: " + GameServer.localAddresses().get(0) + ":" + port;
                    }
                    GameClient client = GameClient.connect(address, port, name);
                    GameServer startedServer = server;
                    String info = hostInfo;
                    SwingUtilities.invokeLater(() -> {
                        frame.dispose();
                        new GameWindow(client, startedServer, info).show();
                    });
                } catch (IOException ex) {
                    if (server != null) server.close();
                    String message = errorText(ex);
                    SwingUtilities.invokeLater(() -> {
                        status.setForeground(new Color(0xC0392B));
                        status.setText(message);
                        playButton.setEnabled(true);
                        frame.pack();
                    });
                }
            }, "arena-connect");
            connector.setDaemon(true);
            connector.start();
        });

        frame.getRootPane().setDefaultButton(playButton);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setContentPane(form);
        frame.pack();
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int row, JComponent left, JComponent right) {
        c.gridy = row;
        if (right == null) {
            c.gridx = 0;
            c.gridwidth = 2;
            panel.add(left, c);
            c.gridwidth = 1;
        } else {
            c.gridx = 0;
            c.weightx = 0;
            panel.add(left, c);
            c.gridx = 1;
            c.weightx = 1;
            panel.add(right, c);
        }
    }

    private static String errorText(IOException e) {
        if (e instanceof BindException) return "Порт занят — возможно, игра уже создана в другом окне";
        if (e instanceof ConnectException) return "Не удалось подключиться: по этому адресу нет сервера";
        if (e instanceof SocketTimeoutException) return "Сервер не отвечает";
        if (e instanceof UnknownHostException) return "Неверный адрес";
        return e.getMessage() == null ? "Ошибка подключения" : e.getMessage();
    }
}
