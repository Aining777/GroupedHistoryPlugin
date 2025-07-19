package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.persistence.Preferences;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.api.montoya.ui.editor.EditorOptions;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

public class BurpExtender implements BurpExtension, ContextMenuItemsProvider {
    private static final String PERSISTENCE_KEY = "grouped_history_data";

    // 定义图标常量
    private static final String ICON_ADD = "➕";
    private static final String ICON_DELETE = "🗑️";
    private static final String ICON_FOLDER = "📁";
    private static final String ICON_FOLDER_OPEN = "📂";
    private static final String ICON_REMOVE = "❌";
    private static final String ICON_SEARCH = "🔍";
    private static final String ICON_EXPORT = "📤";
    private static final String ICON_REFRESH = "🔄";

    // 定义颜色常量
    private static final Color PRIMARY_COLOR = new Color(51, 122, 183);
    private static final Color DANGER_COLOR = new Color(217, 83, 79);
    private static final Color SUCCESS_COLOR = new Color(92, 184, 92);
    private static final Color BACKGROUND_COLOR = new Color(248, 249, 250);
    private static final Color SIDEBAR_COLOR = new Color(240, 240, 240);

    private MontoyaApi api;
    private JPanel mainPanel;
    private JPanel sidebarPanel;
    private JPanel groupListPanel;
    private String currentSelectedGroup;
    private DefaultListModel<HttpRequestResponse> requestListModel;
    private JList<HttpRequestResponse> requestList;
    private HttpRequestEditor requestEditor;
    private HttpResponseEditor responseEditor;
    private Map<String, List<HttpRequestResponse>> groupMap = new HashMap<>();
    private Map<String, JToggleButton> groupButtons = new HashMap<>();
    private JTextField searchField;
    private JLabel statusLabel;
    private ButtonGroup groupButtonGroup;
    private Gson gson;

    // 用于JSON序列化的数据类
    private static class SerializableRequestResponse {
        public String request;
        public String response;

        public SerializableRequestResponse(String request, String response) {
            this.request = request;
            this.response = response;
        }
    }

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        this.gson = new Gson();
        api.extension().setName("Grouped History");
        api.userInterface().registerContextMenuItemsProvider(this);

        SwingUtilities.invokeLater(() -> {
            createUI();
            api.userInterface().registerSuiteTab("Grouped History", mainPanel);
            loadDataFromProject();
        });
    }

    private void createUI() {
        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(BACKGROUND_COLOR);

        // 创建左侧边栏
        createSidebar();

        // 创建主要内容区域
        createMainContentArea();

        // 创建状态栏
        createStatusBar();

        // 组装整体布局
        mainPanel.add(sidebarPanel, BorderLayout.WEST);

        // 初始化
        refreshAllGroupButtons();
    }

    private void createSidebar() {
        sidebarPanel = new JPanel(new BorderLayout());
        sidebarPanel.setBackground(SIDEBAR_COLOR);
        sidebarPanel.setPreferredSize(new Dimension(280, 0));
        sidebarPanel.setBorder(new EmptyBorder(10, 10, 10, 5));

        // 侧边栏标题和工具栏
        JPanel sidebarHeader = new JPanel(new BorderLayout());
        sidebarHeader.setBackground(SIDEBAR_COLOR);

        JLabel titleLabel = new JLabel("分组管理", SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        titleLabel.setBorder(new EmptyBorder(5, 0, 15, 0));

        // 工具栏按钮
        JPanel toolbarPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        toolbarPanel.setBackground(SIDEBAR_COLOR);

        JButton addGroupBtn = createStyledButton(ICON_ADD + " 新建", PRIMARY_COLOR);
        addGroupBtn.setToolTipText("创建新分组");
        addGroupBtn.addActionListener(e -> showAddGroupDialog());

        JButton deleteGroupBtn = createStyledButton(ICON_DELETE + " 删除", DANGER_COLOR);
        deleteGroupBtn.setToolTipText("删除当前分组");
        deleteGroupBtn.addActionListener(e -> deleteCurrentGroup());

        JButton refreshBtn = createStyledButton(ICON_REFRESH, PRIMARY_COLOR);
        refreshBtn.setToolTipText("刷新列表");
        refreshBtn.addActionListener(e -> refreshRequestList());

        toolbarPanel.add(addGroupBtn);
        toolbarPanel.add(deleteGroupBtn);
        toolbarPanel.add(refreshBtn);

        sidebarHeader.add(titleLabel, BorderLayout.NORTH);
        sidebarHeader.add(toolbarPanel, BorderLayout.SOUTH);

        // 搜索框
        JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
        searchPanel.setBackground(SIDEBAR_COLOR);
        searchPanel.setBorder(new EmptyBorder(10, 0, 10, 0));

        JLabel searchIcon = new JLabel(ICON_SEARCH);
        searchField = new JTextField();
        searchField.setToolTipText("搜索分组...");
        searchField.addActionListener(e -> filterGroups());

        searchPanel.add(searchIcon, BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);

        // 分组列表容器
        groupListPanel = new JPanel();
        groupListPanel.setLayout(new BoxLayout(groupListPanel, BoxLayout.Y_AXIS));
        groupListPanel.setBackground(SIDEBAR_COLOR);

        JScrollPane groupScrollPane = new JScrollPane(groupListPanel);
        groupScrollPane.setBorder(null);
        groupScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        groupScrollPane.setBackground(SIDEBAR_COLOR);
        groupScrollPane.getViewport().setBackground(SIDEBAR_COLOR);

        sidebarPanel.add(sidebarHeader, BorderLayout.NORTH);
        sidebarPanel.add(searchPanel, BorderLayout.CENTER);
        sidebarPanel.add(groupScrollPane, BorderLayout.SOUTH);

        // 让分组滚动面板占用主要空间
        sidebarPanel.remove(searchPanel);
        sidebarPanel.remove(groupScrollPane);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBackground(SIDEBAR_COLOR);
        centerPanel.add(searchPanel, BorderLayout.NORTH);
        centerPanel.add(groupScrollPane, BorderLayout.CENTER);

        sidebarPanel.add(centerPanel, BorderLayout.CENTER);
    }

    private void createMainContentArea() {
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBorder(new EmptyBorder(10, 5, 10, 10));

        // 请求列表区域
        JPanel requestPanel = createRequestListPanel();

        // 编辑器区域
        JSplitPane editors = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        editors.setBorder(new TitledBorder("请求/响应详情"));

        requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        editors.setTopComponent(requestEditor.uiComponent());
        editors.setBottomComponent(responseEditor.uiComponent());
        editors.setResizeWeight(0.5);

        // 主要内容分割面板
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, requestPanel, editors);
        mainSplitPane.setResizeWeight(0.4);

        contentPanel.add(mainSplitPane, BorderLayout.CENTER);
        mainPanel.add(contentPanel, BorderLayout.CENTER);
    }

    private JPanel createRequestListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("请求列表"));

        // 请求列表工具栏
        JPanel listToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton removeBtn = createStyledButton(ICON_REMOVE + " 移除选中", DANGER_COLOR);
        removeBtn.setToolTipText("从当前分组中移除选中的请求");
        removeBtn.addActionListener(e -> removeSelectedRequestsFromCurrentGroup());

        JButton exportBtn = createStyledButton(ICON_EXPORT + " 导出", PRIMARY_COLOR);
        exportBtn.setToolTipText("导出当前分组的请求");
        exportBtn.addActionListener(e -> exportCurrentGroup());

        listToolbar.add(removeBtn);
        listToolbar.add(exportBtn);

        // 请求列表
        requestListModel = new DefaultListModel<>();
        requestList = new JList<>(requestListModel);
        requestList.setCellRenderer(new EnhancedRequestResponseCellRenderer());
        requestList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        requestList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                HttpRequestResponse selected = requestList.getSelectedValue();
                if (selected != null) {
                    requestEditor.setRequest(selected.request());
                    if (selected.response() != null) {
                        responseEditor.setResponse(selected.response());
                    } else {
                        responseEditor.setResponse(HttpResponse.httpResponse(""));
                    }
                    updateStatusLabel();
                }
            }
        });

        // 右键菜单
        JPopupMenu requestListPopupMenu = new JPopupMenu();
        JMenuItem removeFromGroupItem = new JMenuItem(ICON_REMOVE + " 从分组中移除");
        removeFromGroupItem.addActionListener(e -> removeSelectedRequestsFromCurrentGroup());
        requestListPopupMenu.add(removeFromGroupItem);
        requestList.setComponentPopupMenu(requestListPopupMenu);

        JScrollPane listScrollPane = new JScrollPane(requestList);
        listScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        panel.add(listToolbar, BorderLayout.NORTH);
        panel.add(listScrollPane, BorderLayout.CENTER);

        return panel;
    }

    private void createStatusBar() {
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(new EmptyBorder(5, 10, 5, 10));
        statusPanel.setBackground(BACKGROUND_COLOR);

        statusLabel = new JLabel("就绪");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));

        statusPanel.add(statusLabel, BorderLayout.WEST);
        mainPanel.add(statusPanel, BorderLayout.SOUTH);
    }

    private JButton createStyledButton(String text, Color backgroundColor) {
        JButton button = new JButton(text);
        button.setBackground(backgroundColor);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        // 添加悬停效果
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(backgroundColor.darker());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(backgroundColor);
            }
        });

        return button;
    }

    private JToggleButton createGroupButton(String groupName) {
        String buttonText = ICON_FOLDER + " " + groupName;
        JToggleButton button = new JToggleButton(buttonText);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
        button.setPreferredSize(new Dimension(260, 35));
        button.setBackground(Color.WHITE);
        button.setBorder(new EmptyBorder(8, 12, 8, 12));
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 12f));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setFocusPainted(false);

        button.addActionListener(e -> {
            currentSelectedGroup = groupName;
            updateSelectedGroupButton(groupName);
            refreshRequestList();
            updateStatusLabel();
        });

        // 添加悬停效果
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (!button.isSelected()) {
                    button.setBackground(new Color(230, 230, 230));
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!button.isSelected()) {
                    button.setBackground(Color.WHITE);
                }
            }
        });

        return button;
    }

    private void updateSelectedGroupButton(String selectedGroupName) {
        for (Map.Entry<String, JToggleButton> entry : groupButtons.entrySet()) {
            String groupName = entry.getKey();
            JToggleButton button = entry.getValue();

            boolean isSelected = groupName.equals(selectedGroupName);
            button.setSelected(isSelected);

            if (isSelected) {
                button.setText(ICON_FOLDER_OPEN + " " + groupName);
                button.setBackground(PRIMARY_COLOR);
                button.setForeground(Color.WHITE);
            } else {
                button.setText(ICON_FOLDER + " " + groupName);
                button.setBackground(Color.WHITE);
                button.setForeground(Color.BLACK);
            }
        }
    }

    private void showAddGroupDialog() {
        String groupName = JOptionPane.showInputDialog(mainPanel, "输入分组名称：", "新建分组", JOptionPane.PLAIN_MESSAGE);
        if (groupName != null && !groupName.trim().isEmpty() && !groupMap.containsKey(groupName)) {
            groupMap.put(groupName, new ArrayList<>());
            addGroupButton(groupName);
            currentSelectedGroup = groupName;
            updateSelectedGroupButton(groupName);
            refreshRequestList();
            saveDataToProject();
            updateStatusLabel();
            showStatusMessage("分组 '" + groupName + "' 创建成功", SUCCESS_COLOR);
        }
    }

    private void deleteCurrentGroup() {
        if (currentSelectedGroup == null || groupMap.isEmpty()) {
            JOptionPane.showMessageDialog(mainPanel, "没有可删除的分组", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int requestCount = groupMap.get(currentSelectedGroup).size();
        int result = JOptionPane.showConfirmDialog(mainPanel,
                "确定要删除分组 '" + currentSelectedGroup + "' 吗？\n这将删除该分组中的 " + requestCount + " 个请求。",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            String deletedGroup = currentSelectedGroup;
            groupMap.remove(currentSelectedGroup);
            refreshAllGroupButtons();
            saveDataToProject();
            showStatusMessage("分组 '" + deletedGroup + "' 已删除", SUCCESS_COLOR);
        }
    }

    private void addGroupButton(String groupName) {
        JToggleButton groupButton = createGroupButton(groupName);
        groupButtons.put(groupName, groupButton);
        groupListPanel.add(groupButton);
        groupListPanel.add(Box.createVerticalStrut(5));
        groupListPanel.revalidate();
        groupListPanel.repaint();
    }

    private void refreshAllGroupButtons() {
        groupListPanel.removeAll();
        groupButtons.clear();
        currentSelectedGroup = null;

        for (String groupName : groupMap.keySet()) {
            addGroupButton(groupName);
        }

        if (!groupMap.isEmpty()) {
            currentSelectedGroup = groupMap.keySet().iterator().next();
            updateSelectedGroupButton(currentSelectedGroup);
            refreshRequestList();
        } else {
            requestListModel.clear();
            showStatusMessage("暂无分组", null);
        }

        groupListPanel.revalidate();
        groupListPanel.repaint();
    }

    private void refreshRequestList() {
        requestListModel.clear();
        if (currentSelectedGroup != null) {
            List<HttpRequestResponse> requests = groupMap.getOrDefault(currentSelectedGroup, new ArrayList<>());
            for (HttpRequestResponse item : requests) {
                requestListModel.addElement(item);
            }
        }
        updateStatusLabel();
    }

    private void updateStatusLabel() {
        if (currentSelectedGroup == null) {
            statusLabel.setText("就绪");
        } else {
            int totalRequests = groupMap.getOrDefault(currentSelectedGroup, new ArrayList<>()).size();
            int selectedRequests = requestList.getSelectedIndices().length;
            if (selectedRequests > 0) {
                statusLabel.setText("分组: " + currentSelectedGroup + " | 总数: " + totalRequests + " | 已选择: " + selectedRequests);
            } else {
                statusLabel.setText("分组: " + currentSelectedGroup + " | 总数: " + totalRequests + " 个请求");
            }
        }
    }

    private void showStatusMessage(String message, Color color) {
        statusLabel.setText(message);
        if (color != null) {
            statusLabel.setForeground(color);
            // 3秒后恢复默认状态
            Timer timer = new Timer(3000, e -> {
                updateStatusLabel();
                statusLabel.setForeground(Color.BLACK);
            });
            timer.setRepeats(false);
            timer.start();
        }
    }

    private void filterGroups() {
        String searchText = searchField.getText().toLowerCase().trim();

        for (Map.Entry<String, JToggleButton> entry : groupButtons.entrySet()) {
            String groupName = entry.getKey();
            JToggleButton button = entry.getValue();
            boolean matches = searchText.isEmpty() || groupName.toLowerCase().contains(searchText);
            button.setVisible(matches);
        }

        groupListPanel.revalidate();
        groupListPanel.repaint();
    }

    private void removeSelectedRequestsFromCurrentGroup() {
        if (currentSelectedGroup == null) {
            JOptionPane.showMessageDialog(mainPanel, "请先选择一个分组", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        List<HttpRequestResponse> selectedRequests = requestList.getSelectedValuesList();
        if (selectedRequests.isEmpty()) {
            JOptionPane.showMessageDialog(mainPanel, "请先选择要移除的请求", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int result = JOptionPane.showConfirmDialog(mainPanel,
                "确定要从分组 '" + currentSelectedGroup + "' 中移除 " + selectedRequests.size() + " 个请求吗？",
                "确认移除", JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            List<HttpRequestResponse> groupList = groupMap.get(currentSelectedGroup);
            for (HttpRequestResponse request : selectedRequests) {
                groupList.remove(request);
            }
            refreshRequestList();
            saveDataToProject();
            showStatusMessage("已移除 " + selectedRequests.size() + " 个请求", SUCCESS_COLOR);
        }
    }

    private void exportCurrentGroup() {
        if (currentSelectedGroup == null || groupMap.get(currentSelectedGroup).isEmpty()) {
            JOptionPane.showMessageDialog(mainPanel, "当前分组为空，无法导出", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 这里可以实现导出功能
        showStatusMessage("导出功能开发中...", PRIMARY_COLOR);
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> menuItems = new ArrayList<>();

        if (event.selectedRequestResponses().size() > 0) {
            JMenuItem item = new JMenuItem(ICON_FOLDER + " 发送到分组历史");
            item.addActionListener(e -> {
                List<HttpRequestResponse> messages = event.selectedRequestResponses();
                if (messages.isEmpty()) return;

                String[] groupNames = groupMap.keySet().toArray(new String[0]);
                String group = (String) JOptionPane.showInputDialog(
                        null,
                        "选择或输入分组：",
                        "发送到分组",
                        JOptionPane.PLAIN_MESSAGE,
                        null,
                        groupNames,
                        null
                );

                if (group != null && !group.trim().isEmpty()) {
                    groupMap.putIfAbsent(group, new ArrayList<>());
                    for (HttpRequestResponse message : messages) {
                        groupMap.get(group).add(message);
                    }

                    if (!groupButtons.containsKey(group)) {
                        addGroupButton(group);
                    }

                    currentSelectedGroup = group;
                    updateSelectedGroupButton(group);
                    refreshRequestList();
                    saveDataToProject();
                    showStatusMessage("已添加 " + messages.size() + " 个请求到分组 '" + group + "'", SUCCESS_COLOR);
                }
            });
            menuItems.add(item);
        }

        return menuItems;
    }

    // 修复的保存方法 - 使用 Preferences API
    private void saveDataToProject() {
        try {
            Preferences preferences = api.persistence().preferences();
            Map<String, List<SerializableRequestResponse>> serializableData = new HashMap<>();

            for (Map.Entry<String, List<HttpRequestResponse>> entry : groupMap.entrySet()) {
                String groupName = entry.getKey();
                List<HttpRequestResponse> requests = entry.getValue();
                List<SerializableRequestResponse> serializableRequests = new ArrayList<>();

                for (HttpRequestResponse requestResponse : requests) {
                    String requestBase64 = null;
                    String responseBase64 = null;

                    if (requestResponse.request() != null) {
                        requestBase64 = Base64.getEncoder().encodeToString(
                                requestResponse.request().toByteArray().getBytes());
                    }

                    if (requestResponse.response() != null) {
                        responseBase64 = Base64.getEncoder().encodeToString(
                                requestResponse.response().toByteArray().getBytes());
                    }

                    serializableRequests.add(new SerializableRequestResponse(requestBase64, responseBase64));
                }

                serializableData.put(groupName, serializableRequests);
            }

            String jsonData = gson.toJson(serializableData);
            preferences.setString(PERSISTENCE_KEY, jsonData);

        } catch (Exception e) {
            api.logging().logToError("Failed to save grouped history data: " + e.getMessage());
        }
    }

    // 修复的加载方法 - 使用 Preferences API
    private void loadDataFromProject() {
        try {
            Preferences preferences = api.persistence().preferences();
            String jsonData = preferences.getString(PERSISTENCE_KEY);

            if (jsonData == null || jsonData.trim().isEmpty()) {
                return;
            }

            Type type = new TypeToken<Map<String, List<SerializableRequestResponse>>>(){}.getType();
            Map<String, List<SerializableRequestResponse>> serializableData = gson.fromJson(jsonData, type);

            groupMap.clear();

            for (Map.Entry<String, List<SerializableRequestResponse>> entry : serializableData.entrySet()) {
                String groupName = entry.getKey();
                List<SerializableRequestResponse> serializableRequests = entry.getValue();
                List<HttpRequestResponse> requests = new ArrayList<>();

                for (SerializableRequestResponse serializableRequest : serializableRequests) {
                    try {
                        HttpRequest request = null;
                        HttpResponse response = null;

                        if (serializableRequest.request != null) {
                            byte[] requestBytes = Base64.getDecoder().decode(serializableRequest.request);
                            request = HttpRequest.httpRequest(new String(requestBytes, StandardCharsets.UTF_8));
                        }

                        if (serializableRequest.response != null) {
                            byte[] responseBytes = Base64.getDecoder().decode(serializableRequest.response);
                            response = HttpResponse.httpResponse(new String(responseBytes, StandardCharsets.UTF_8));
                        }

                        if (request != null) {
                            HttpRequestResponse requestResponse = HttpRequestResponse.httpRequestResponse(request, response);
                            requests.add(requestResponse);
                        }

                    } catch (Exception e) {
                        api.logging().logToError("Failed to load request from group " + groupName + ": " + e.getMessage());
                    }
                }

                if (!requests.isEmpty()) {
                    groupMap.put(groupName, requests);
                }
            }

            refreshAllGroupButtons();

        } catch (Exception e) {
            api.logging().logToError("Failed to load grouped history data: " + e.getMessage());
        }
    }

    // 增强的请求响应单元格渲染器
    private static class EnhancedRequestResponseCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof HttpRequestResponse) {
                HttpRequestResponse message = (HttpRequestResponse) value;
                HttpRequest request = message.request();
                HttpResponse response = message.response();

                String method = request.method();
                String path = request.path();
                if (request.query() != null && !request.query().isEmpty()) {
                    path += "?" + request.query();
                }

                String status = "";
                if (response != null) {
                    status = " [" + response.statusCode() + "]";
                }

                setText(method + " " + path + status);

                // 根据HTTP方法设置图标
                String methodIcon = getMethodIcon(method);
                setText(methodIcon + " " + getText());

                // 根据状态码设置颜色
                if (response != null) {
                    int statusCode = response.statusCode();
                    if (statusCode >= 200 && statusCode < 300) {
                        setForeground(isSelected ? Color.WHITE : new Color(0, 128, 0));
                    } else if (statusCode >= 400) {
                        setForeground(isSelected ? Color.WHITE : new Color(220, 20, 60));
                    }
                }

                setBorder(new EmptyBorder(5, 10, 5, 10));
            }
            return this;
        }

        private String getMethodIcon(String method) {
            switch (method.toUpperCase()) {
                case "GET": return "📄";
                case "POST": return "📝";
                case "PUT": return "✏️";
                case "DELETE": return "🗑️";
                case "PATCH": return "🔧";
                default: return "📋";
            }
        }
    }
}
