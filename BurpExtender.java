package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class BurpExtender implements BurpExtension, ContextMenuItemsProvider {
    private static final String PERSISTENCE_KEY = "grouped_history_data";
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

            // 项目加载时恢复数据
            loadDataFromProject();
        });
    }

    private void createUI() {
        mainPanel = new JPanel(new BorderLayout());

        // 顶部分组控件布局
        JPanel topPanel = new JPanel(new BorderLayout());

        // 左侧：用于显示分组按钮的面板
        groupButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        topPanel.add(groupButtonsPanel, BorderLayout.WEST);

        // 右侧：新增和删除分组按钮
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
                saveDataToProject(); // 保存到项目
            }
        });

        JButton deleteGroupBtn = new JButton("删除分组");
        deleteGroupBtn.addActionListener(e -> {
            if (currentSelectedGroup == null || groupMap.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "没有可删除的分组", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            int result = JOptionPane.showConfirmDialog(mainPanel,
                    "确定要删除分组 '" + currentSelectedGroup + "' 吗？\n这将删除该分组中的所有请求。",
                    "确认删除", JOptionPane.YES_NO_OPTION);

            if (result == JOptionPane.YES_OPTION) {
                groupMap.remove(currentSelectedGroup);
                refreshAllGroupButtons();
                saveDataToProject(); // 保存到项目
            }
        });

        rightPanel.add(addGroupBtn);
        rightPanel.add(deleteGroupBtn);
        topPanel.add(rightPanel, BorderLayout.EAST);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 中部请求列表
        requestListModel = new DefaultListModel<>();
        requestList = new JList<>(requestListModel);
        requestList.setCellRenderer(new RequestResponseCellRenderer());
        requestList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); // 允许多选
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

        // 为请求列表添加右键菜单
        JPopupMenu requestListPopupMenu = new JPopupMenu();
        JMenuItem removeFromGroupItem = new JMenuItem("从当前分组中移除");
        removeFromGroupItem.addActionListener(e -> removeSelectedRequestsFromCurrentGroup());
        requestListPopupMenu.add(removeFromGroupItem);
        requestList.setComponentPopupMenu(requestListPopupMenu);

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

    // 新增：从当前分组中移除选中的请求
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
            saveDataToProject(); // 保存到项目
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
                        saveDataToProject(); // 保存到项目
                    }
                }
            });
            menuItems.add(item);
        }

        return menuItems;
    }

    // 保存数据到项目文件
    private void saveDataToProject() {
        try {
            PersistedObject persistedObject = PersistedObject.persistedObject();

            for (Map.Entry<String, List<HttpRequestResponse>> entry : groupMap.entrySet()) {
                String groupName = entry.getKey();
                List<HttpRequestResponse> requests = entry.getValue();

                PersistedObject groupObject = PersistedObject.persistedObject();

                for (int i = 0; i < requests.size(); i++) {
                    HttpRequestResponse requestResponse = requests.get(i);
                    PersistedObject requestObject = PersistedObject.persistedObject();

                    // 保存请求数据
                    if (requestResponse.request() != null) {
                        String requestBase64 = Base64.getEncoder().encodeToString(
                                requestResponse.request().toByteArray().getBytes());
                        requestObject.setString("request", requestBase64);
                    }

                    // 保存响应数据
                    if (requestResponse.response() != null) {
                        String responseBase64 = Base64.getEncoder().encodeToString(
                                requestResponse.response().toByteArray().getBytes());
                        requestObject.setString("response", responseBase64);
                    }

                    groupObject.setObject("request_" + i, requestObject);
                }

                groupObject.setInteger("count", requests.size());
                persistedObject.setObject(groupName, groupObject);
            }

            api.persistence().extensionData().setObject(PERSISTENCE_KEY, persistedObject);

        } catch (Exception e) {
            api.logging().logToError("Failed to save grouped history data: " + e.getMessage());
        }
    }

    // 从项目文件加载数据
    private void loadDataFromProject() {
        try {
            PersistedObject persistedObject = api.persistence().extensionData().getObject(PERSISTENCE_KEY);
            if (persistedObject == null) {
                return;
            }

            groupMap.clear();

            for (String groupName : persistedObject.objectKeys()) {
                PersistedObject groupObject = persistedObject.getObject(groupName);
                if (groupObject == null) continue;

                List<HttpRequestResponse> requests = new ArrayList<>();
                Integer count = groupObject.getInteger("count");
                if (count == null) continue;

                for (int i = 0; i < count; i++) {
                    PersistedObject requestObject = groupObject.getObject("request_" + i);
                    if (requestObject == null) continue;

                    try {
                        HttpRequest request = null;
                        HttpResponse response = null;

                        String requestBase64 = requestObject.getString("request");
                        if (requestBase64 != null) {
                            byte[] requestBytes = Base64.getDecoder().decode(requestBase64);
                            request = HttpRequest.httpRequest(new String(requestBytes, StandardCharsets.UTF_8));
                        }

                        String responseBase64 = requestObject.getString("response");
                        if (responseBase64 != null) {
                            byte[] responseBytes = Base64.getDecoder().decode(responseBase64);
                            response = HttpResponse.httpResponse(new String(responseBytes, StandardCharsets.UTF_8));
                        }

                        if (request != null) {
                            HttpRequestResponse requestResponse = HttpRequestResponse.httpRequestResponse(request, response);
                            requests.add(requestResponse);
                        }

                    } catch (Exception e) {
                        api.logging().logToError("Failed to load request " + i + " from group " + groupName + ": " + e.getMessage());
                    }
                }

                if (!requests.isEmpty()) {
                    groupMap.put(groupName, requests);
                }
            }

            // 刷新UI
            refreshAllGroupButtons();

        } catch (Exception e) {
            api.logging().logToError("Failed to load grouped history data: " + e.getMessage());
        }
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