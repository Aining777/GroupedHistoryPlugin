package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.api.montoya.ui.editor.EditorOptions;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BurpExtender implements BurpExtension, ContextMenuItemsProvider {
    private MontoyaApi api;
    private JPanel mainPanel;
    private JPanel groupButtonsPanel;
    private String currentSelectedGroup;
    private DefaultListModel<HttpRequestResponse> requestListModel;
    private JList<HttpRequestResponse> requestList;
    private HttpRequestEditor requestEditor;
    private HttpResponseEditor responseEditor;
    private Map<String, List<HttpRequestResponse>> groupMap = new HashMap<>();

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("Grouped History");
        api.userInterface().registerContextMenuItemsProvider(this);

        SwingUtilities.invokeLater(() -> {
            createUI();
            api.userInterface().registerSuiteTab("Grouped History", mainPanel);
        });
    }

    private void createUI() {
        mainPanel = new JPanel(new BorderLayout());

        // 顶部分组控件布局
        JPanel topPanel = new JPanel(new BorderLayout());

        // 左侧：用于显示分组按钮的面板
        groupButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        topPanel.add(groupButtonsPanel, BorderLayout.WEST);

        // 右侧：新增分组按钮
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton addGroupBtn = new JButton("新增分组");
        addGroupBtn.addActionListener(e -> {
            String groupName = JOptionPane.showInputDialog("输入分组名：");
            if (groupName != null && !groupName.trim().isEmpty() && !groupMap.containsKey(groupName)) {
                groupMap.put(groupName, new ArrayList<>());
                addOrUpdateGroupButton(groupName);
                currentSelectedGroup = groupName;
                refreshGroupButtonsState();
                refreshRequestList();
            }
        });
        rightPanel.add(addGroupBtn);
        topPanel.add(rightPanel, BorderLayout.EAST);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 中部请求列表
        requestListModel = new DefaultListModel<>();
        requestList = new JList<>(requestListModel);
        requestList.setCellRenderer(new RequestResponseCellRenderer());
        requestList.addListSelectionListener(e -> {
            HttpRequestResponse selected = requestList.getSelectedValue();
            if (selected != null) {
                requestEditor.setRequest(selected.request());
                if (selected.response() != null) {
                    responseEditor.setResponse(selected.response());
                } else {
                    responseEditor.setResponse(HttpResponse.httpResponse(""));
                }
            }
        });
        JScrollPane listScrollPane = new JScrollPane(requestList);

        // 底部 请求/响应查看器
        JSplitPane editors = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);
        editors.setTopComponent(requestEditor.uiComponent());
        editors.setBottomComponent(responseEditor.uiComponent());

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScrollPane, editors);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        // 初始化时刷新分组按钮
        refreshAllGroupButtons();
    }

    private void addOrUpdateGroupButton(String groupName) {
        // 检查是否已存在该分组的按钮，避免重复添加
        for (Component comp : groupButtonsPanel.getComponents()) {
            if (comp instanceof JToggleButton) {
                JToggleButton btn = (JToggleButton) comp;
                if (btn.getText().equals(groupName)) {
                    return; // 按钮已存在，不重复添加
                }
            }
        }

        JToggleButton groupButton = new JToggleButton(groupName);
        groupButton.addActionListener(e -> {
            currentSelectedGroup = ((JToggleButton) e.getSource()).getText();
            refreshGroupButtonsState();
            refreshRequestList();
        });
        groupButtonsPanel.add(groupButton);
        groupButtonsPanel.revalidate();
        groupButtonsPanel.repaint();
    }

    private void refreshGroupButtonsState() {
        for (Component comp : groupButtonsPanel.getComponents()) {
            if (comp instanceof JToggleButton) {
                JToggleButton btn = (JToggleButton) comp;
                btn.setSelected(btn.getText().equals(currentSelectedGroup));
            }
        }
    }

    private void refreshAllGroupButtons() {
        groupButtonsPanel.removeAll();
        currentSelectedGroup = null;
        for (String groupName : groupMap.keySet()) {
            addOrUpdateGroupButton(groupName);
        }
        if (!groupMap.isEmpty()) {
            currentSelectedGroup = groupMap.keySet().iterator().next();
            refreshGroupButtonsState();
            refreshRequestList();
        } else {
            requestListModel.clear();
        }
    }

    private void refreshRequestList() {
        requestListModel.clear();
        if (currentSelectedGroup != null) {
            for (HttpRequestResponse item : groupMap.getOrDefault(currentSelectedGroup, new ArrayList<>())) {
                requestListModel.addElement(item);
            }
        }
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> menuItems = new ArrayList<>();

        // 只在有选中的请求时显示菜单项
        if (event.selectedRequestResponses().size() > 0) {
            JMenuItem item = new JMenuItem("Send to Grouped History");
            item.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    List<HttpRequestResponse> messages = event.selectedRequestResponses();
                    if (messages.isEmpty()) return;

                    // 使用 JOptionPane 来让用户选择或输入分组
                    String group = (String) JOptionPane.showInputDialog(
                            null,
                            "选择或输入分组：",
                            "发送到分组",
                            JOptionPane.PLAIN_MESSAGE,
                            null,
                            groupMap.keySet().toArray(),
                            null
                    );

                    if (group != null && !group.trim().isEmpty()) {
                        groupMap.putIfAbsent(group, new ArrayList<>());
                        for (HttpRequestResponse message : messages) {
                            groupMap.get(group).add(message);
                        }
                        // 更新分组按钮显示
                        addOrUpdateGroupButton(group);
                        currentSelectedGroup = group;
                        refreshGroupButtonsState();
                        refreshRequestList();
                    }
                }
            });
            menuItems.add(item);
        }

        return menuItems;
    }

    private static class RequestResponseCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof HttpRequestResponse) {
                HttpRequestResponse message = (HttpRequestResponse) value;
                HttpRequest request = message.request();
                String method = request.method();
                String path = request.path();
                if (request.query() != null && !request.query().isEmpty()) {
                    path += "?" + request.query();
                }
                setText(method + " " + path);
            }
            return this;
        }
    }
}
