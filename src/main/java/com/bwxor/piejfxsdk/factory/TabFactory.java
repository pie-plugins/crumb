package com.bwxor.piejfxsdk.factory;

import com.bwxor.piejfxsdk.control.AssistantMessageView;
import com.bwxor.piejfxsdk.dto.Session;
import com.bwxor.piejfxsdk.mapper.MapLargeNumbersToString;
import com.bwxor.piejfxsdk.state.*;
import com.bwxor.piejfxsdk.type.Role;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

public class TabFactory {
    public static Tab createClaudeAgentTab() {
        Tab tab = new Tab("Crumb");

        SplitPane mainSplitPane = new SplitPane();
        mainSplitPane.setOrientation(Orientation.VERTICAL);
        mainSplitPane.setDividerPosition(0, 0.75);

        tab.setContent(mainSplitPane);

        mainSplitPane.getItems().add(createClaudeAnswerVBox());
        mainSplitPane.getItems().add(createClaudeAgentChatPane());

        return tab;
    }

    private static VBox createClaudeAnswerVBox() {
        UIState uiState = UIState.instance;

        var vBox = new VBox();
        VBox.setVgrow(vBox, Priority.ALWAYS);

        uiState.setClaudeAnswerHomeVBox(createClaudeAnswerHomeVBox());
        uiState.setClaudeAnswerTextVBox(createClaudeAnswerTextVBox());

        vBox.getChildren().add(uiState.getClaudeAnswerHomeVBox());
        vBox.getChildren().add(uiState.getClaudeAnswerTextVBox());

        uiState.getClaudeAnswerTextVBox().setVisible(false);
        uiState.getClaudeAnswerTextVBox().setManaged(false);

        return vBox;
    }

    private static VBox createClaudeAnswerHomeVBox() {
        UIState uiState = UIState.instance;
        ServiceState serviceState = ServiceState.instance;

        var vBox = new VBox();
        vBox.setAlignment(Pos.CENTER);
        VBox.setVgrow(vBox, Priority.ALWAYS);
        vBox.setPadding(new Insets(15));
        vBox.setSpacing(10);

        ImageView imageView = new ImageView(new Image(serviceState.getResourceService().getResourceByNameAsStream("img/agent_icon.png")));
        imageView.setFitWidth(125);
        imageView.setFitHeight(125);
        imageView.setPreserveRatio(true);

        Label label = new Label("Hi there, I'm your specialized AI Agent for Pie. How can I help you today?");
        label.setFont(javafx.scene.text.Font.font(14));
        label.setWrapText(true);
        label.setAlignment(Pos.BASELINE_CENTER);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setMaxWidth(Double.MAX_VALUE);

        ScrollPane sessionScrollPane = new ScrollPane();
        sessionScrollPane.setFitToWidth(true);
        sessionScrollPane.setMaxWidth(Double.MAX_VALUE);
        VBox sessionVBox = new VBox();
        sessionVBox.setSpacing(5);
        uiState.setSessionVBox(sessionVBox);

        sessionScrollPane.setContent(sessionVBox);

        vBox.getChildren().add(imageView);
        vBox.getChildren().add(label);
        vBox.getChildren().add(sessionScrollPane);

        return vBox;
    }

    private static VBox createClaudeAnswerTextVBox() {
        UIState uiState = UIState.instance;
        ServiceState serviceState = ServiceState.instance;
        SessionState sessionState = SessionState.instance;

        var vBox = new VBox();
        VBox.setVgrow(vBox, Priority.ALWAYS);
        vBox.setPadding(new Insets(3));
        vBox.setSpacing(3);

        var hBox = new HBox(5);
        var region = new Region(); // for button to float right
        HBox.setHgrow(region, Priority.ALWAYS);

        var deleteButton = new Button("Delete");
        deleteButton.setOnMouseClicked(
                e -> {
                    sessionState.getSessions().remove(sessionState.getCurrentSessionIndex());
                    uiState.getSessionVBox().getChildren().remove(sessionState.getCurrentSessionIndex());
                    showAllSessions();
                    uiState.getClaudePromptTextArea().setEditable(true);
                }
        );

        var closeButton = new Button("Close");
        closeButton.setOnMouseClicked(
                e -> {
                    showAllSessions();
                }
        );

        hBox.getChildren().addAll(region, deleteButton, closeButton);

        var tokenUsageLabel = new Label("0");
        uiState.setTokenUsageLabel(tokenUsageLabel);
        var tokenProgressBar = new ProgressBar();
        tokenProgressBar.setProgress(0);
        tokenProgressBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tokenProgressBar, Priority.ALWAYS);
        uiState.setTokenProgressBar(tokenProgressBar);

        var tokenUsageHBox = new HBox(8, tokenUsageLabel, tokenProgressBar);
        tokenUsageHBox.setPadding(new Insets(4, 8, 4, 8));
        tokenUsageHBox.setAlignment(Pos.CENTER_RIGHT);

        var scrollPane = new ScrollPane();
        uiState.setMessageHistoryScrollPane(scrollPane);

        var messageHistoryVBox = new VBox();
        messageHistoryVBox.setSpacing(5);
        messageHistoryVBox.getStylesheets().add(serviceState.getResourceService().getResourceByName("styles/styles.css").toExternalForm());
        scrollPane.setContent(messageHistoryVBox);
        scrollPane.setFitToWidth(true);

        uiState.setMessageHistoryVBox(messageHistoryVBox);
        // Scroll after layout so the new content height is taken into account (avoids a gap at the bottom)
        messageHistoryVBox.heightProperty().addListener((_, _, _) -> Platform.runLater(() -> scrollPane.setVvalue(1.0)));

        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        vBox.getChildren().addAll(hBox, tokenUsageHBox, scrollPane);

        return vBox;
    }

    private static VBox createClaudeAgentChatPane() {
        UIState uiState = UIState.instance;
        ServiceState serviceState = ServiceState.instance;
        SessionState sessionState = SessionState.instance;

        var textArea = new TextArea();
        textArea.setPromptText("How can I help you today?");
        textArea.setWrapText(true);
        textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode().equals(KeyCode.ENTER) && !e.isShiftDown()) {
                if (serviceState.getClaudeMessageService() == null) {
                    textArea.clear();
                    textArea.setEditable(true);
                    serviceState.getNotificationService().showNotificationOk("Please insert your Claude API Key in the configuration file.");
                    return;
                }

                // Create new session if no chat has been opened
                if (sessionState.getCurrentSessionIndex() == -1) {
                    sessionState.getSessions().add(new Session());
                    final int currentSessionIndex = sessionState.getSessions().size() - 1;
                    sessionState.setCurrentSessionIndex(currentSessionIndex);
                    showSessionHistory();

                    String buttonText = textArea.getText();
                    var currentSessionButton = new Button(buttonText);
                    currentSessionButton.setMinHeight(30);
                    currentSessionButton.setMaxHeight(60);
                    currentSessionButton.setWrapText(true);
                    currentSessionButton.setMaxWidth(Double.MAX_VALUE);
                    currentSessionButton.setOnMouseClicked(_ -> {
                        int currentIndex;
                        for (currentIndex = 0; uiState.getSessionVBox().getChildren().get(currentIndex) != currentSessionButton && currentIndex < uiState.getSessionVBox().getChildren().size(); currentIndex++)
                            ;

                        sessionState.setCurrentSessionIndex(currentIndex);
                        showSessionHistory();
                    });
                    uiState.getSessionVBox().getChildren().add(currentSessionButton);
                }

                String text = textArea.getSelectedText().isEmpty() ? textArea.getText() : textArea.getSelectedText();
                textArea.setEditable(false);
                textArea.clear();

                uiState.getMessageHistoryVBox().getChildren().add(MessageFactory.createUserMessage(text));

                Thread thread = new Thread(createAssistantTask(textArea, text), "claude-chat-request");
                thread.setDaemon(true);
                thread.start();

                e.consume();
            }
        });
        uiState.setClaudePromptTextArea(textArea);

        VBox.setVgrow(textArea, Priority.ALWAYS);
        var vbox = new VBox(textArea);
        return vbox;
    }

    private static Task createAssistantTask(TextArea textArea, String text) {
        ServiceState serviceState = ServiceState.instance;
        ConfigurationState configurationState = ConfigurationState.instance;
        UIState uiState = UIState.instance;
        OpenedFolderState openedFolderState = OpenedFolderState.instance;

        final AssistantMessageView markdownView = MessageFactory.createAssistantMessage("");
        uiState.getMessageHistoryVBox().getChildren().add(markdownView);

        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                String result[] = new String[1];
                final AssistantMessageView[] currentMarkdown = {markdownView};

                final Node[] workingMessage = {null};

                serviceState.getClaudeMessageService().sendAgentic(text, configurationState.getMaxIterations(), t ->
                                Platform.runLater(() -> {
                                    if (currentMarkdown[0] == null) {
                                        currentMarkdown[0] = MessageFactory.createAssistantMessage("");
                                        uiState.getMessageHistoryVBox().getChildren().add(currentMarkdown[0]);
                                    }
                                    currentMarkdown[0].setMdString(currentMarkdown[0].getMdString() + t);
                                    uiState.getMessageHistoryScrollPane().setVvalue(1.0);
                                }),
                        (toolUseDisplayItem -> Platform.runLater(() -> {
                            uiState.getMessageHistoryVBox().getChildren().add(
                                    MessageFactory.createToolUseMessage(toolUseDisplayItem.toolName(), toolUseDisplayItem.inputs()));
                            currentMarkdown[0] = null;
                            uiState.getMessageHistoryScrollPane().setVvalue(1.0);
                        })),
                        (toggle) -> Platform.runLater(() -> {
                            var children = uiState.getClaudeAnswerTextVBox().getChildren();
                            if (toggle) {
                                if (workingMessage[0] == null) {
                                    workingMessage[0] = MessageFactory.createWorkingMessage();
                                    children.add(workingMessage[0]);
                                }
                            } else if (workingMessage[0] != null) {
                                children.remove(workingMessage[0]);
                                workingMessage[0] = null;
                            }
                        }),
                        (progress) -> Platform.runLater(() -> uiState.getTokenProgressBar().setProgress(progress)),
                        (usedTokens) -> Platform.runLater(() -> uiState.getTokenUsageLabel().setText(MapLargeNumbersToString.map(usedTokens))),
                        (errorMessage) -> Platform.runLater(() -> {
                            uiState.getMessageHistoryVBox().getChildren().add(
                                    MessageFactory.createErrorMessage(errorMessage));
                            currentMarkdown[0] = null;
                            uiState.getMessageHistoryScrollPane().setVvalue(1.0);
                        }),
                        () -> Platform.runLater(() -> serviceState.getFileService().openFolder(openedFolderState.getOpenedFolder()))
                );

                return result[0];
            }
        };

        task.setOnSucceeded(_ -> textArea.setEditable(true));

        return task;
    }

    private static void showSessionHistory() {
        UIState uiState = UIState.instance;
        SessionState sessionState = SessionState.instance;

        uiState.getMessageHistoryVBox().getChildren().clear();

        var currentSession = sessionState.getSessions().get(sessionState.getCurrentSessionIndex());

        if (currentSession != null && currentSession.getMessages() != null) {
            for (var message : currentSession.getMessages()) {
                if (message.role().equals(Role.USER)) {
                    uiState.getMessageHistoryVBox().getChildren().add(MessageFactory.createUserMessage(message.content()));
                } else if (message.role().equals(Role.ASSISTANT)) {
                    uiState.getMessageHistoryVBox().getChildren().add(MessageFactory.createAssistantMessage(message.content()));
                } else if (message.role().equals(Role.TOOL_USE)) {
                    uiState.getMessageHistoryVBox().getChildren().add(MessageFactory.createToolUseMessage(message.content(), message.inputs()));
                }
            }
        }

        uiState.getClaudeAnswerHomeVBox().setVisible(false);
        uiState.getClaudeAnswerHomeVBox().setManaged(false);
        uiState.getClaudeAnswerTextVBox().setVisible(true);
        uiState.getClaudeAnswerTextVBox().setManaged(true);
    }

    private static void showAllSessions() {
        UIState uiState = UIState.instance;
        SessionState sessionState = SessionState.instance;

        uiState.getClaudeAnswerTextVBox().setVisible(false);
        uiState.getClaudeAnswerTextVBox().setManaged(false);
        uiState.getClaudeAnswerHomeVBox().setVisible(true);
        uiState.getClaudeAnswerHomeVBox().setManaged(true);
        sessionState.setCurrentSessionIndex(-1);

        if (uiState.getClaudePromptTextArea() != null) {
            uiState.getClaudePromptTextArea().setEditable(true);
        }
    }

}
