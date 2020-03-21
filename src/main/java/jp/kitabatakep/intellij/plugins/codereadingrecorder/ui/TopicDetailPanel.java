package jp.kitabatakep.intellij.plugins.codereadingrecorder.ui;

import com.google.wireless.android.sdk.stats.IntellijProjectSizeStats;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.impl.EditorFactoryImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.RowsDnDSupport;
import com.intellij.ui.components.JBList;
import com.intellij.ui.popup.util.DetailView;
import com.intellij.ui.popup.util.DetailViewImpl;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.ui.EditableModel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import jp.kitabatakep.intellij.plugins.codereadingrecorder.AppConstants;
import jp.kitabatakep.intellij.plugins.codereadingrecorder.Topic;
import jp.kitabatakep.intellij.plugins.codereadingrecorder.TopicLine;
import jp.kitabatakep.intellij.plugins.codereadingrecorder.TopicNotifier;
import jp.kitabatakep.intellij.plugins.codereadingrecorder.actions.TopicLineDeleteAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Iterator;

class TopicDetailPanel extends JPanel implements Disposable
{
    private Project project;
    private final EditorTextField memoArea = new EditorTextField();

    private JBList<TopicLine> topicLineList;
    private TopicLineListModel<TopicLine> topicLineListModel = new TopicLineListModel<>();

    private MyDetailView detailView;

    private Topic topic;
    private TopicLine selectedTopicLine;


    public TopicDetailPanel(Project project)
    {
        super(new BorderLayout());
        Disposer.register(project, this);

        this.project = project;

        initTopicLineList();
        detailView = new MyDetailView(project);

        JBSplitter splitPane = new JBSplitter(0.3f);
        splitPane.setSplitterProportionKey(AppConstants.appName + "TopicDetailPanel.splitter");
        splitPane.setFirstComponent(topicLineList);
        splitPane.setSecondComponent(detailView);

        memoArea.getDocument().addDocumentListener(new MemoAreaListener(this));
//        memoArea.setFileType(IntellijProjectSizeStats.FileType.UNKNOWN_FILE_TYPE);
        add(memoArea, BorderLayout.NORTH);

        add(splitPane);

        MessageBus messageBus = project.getMessageBus();
        messageBus.connect().subscribe(TopicNotifier.TOPIC_NOTIFIER_TOPIC, new TopicNotifier(){
            @Override
            public void lineDeleted(Topic _topic, TopicLine _topicLine)
            {
                if (_topic == topic) {
                    topicLineListModel.removeElement(_topicLine);
                }
            }

            @Override
            public void lineAdded(Topic _topic, TopicLine _topicLine)
            {
                if (_topic == topic) {
                    topicLineListModel.addElement(_topicLine);
                }
            }
        });
    }

    @Override
    public void dispose()
    {
        if (memoArea.getEditor() != null) {
            EditorFactory.getInstance().releaseEditor(memoArea.getEditor());
        }
    }

    private static class MemoAreaListener implements DocumentListener
    {
        TopicDetailPanel topicDetailPanel;

        private MemoAreaListener(TopicDetailPanel topicDetailPanel)
        {
            this.topicDetailPanel = topicDetailPanel;
        }

        public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent e)
        {
            Document doc = e.getDocument();
            topicDetailPanel.topic.setMemo(doc.getText());
        }
    }

    private void initTopicLineList()
    {
        topicLineList = new JBList<>();
        topicLineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        topicLineList.setCellRenderer(new TopicLineListCellRenderer<>(project));
        topicLineList.addListSelectionListener(e -> {
            TopicLine topicLine = topicLineList.getSelectedValue();
            if (topicLine == null) {
                detailView.clearEditor();
            } else if (selectedTopicLine == null || topicLine != selectedTopicLine) {
                selectedTopicLine = topicLine;
                detailView.navigateInPreviewEditor(DetailView.PreviewEditorState.create(topicLine.file(), topicLine.line()));
            }
        });

        topicLineList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    TopicLine topicLine = topicLineList.getSelectedValue();
                    ActionUtil.performActionDumbAware(
                        new TopicLineDeleteAction(project, (v) -> { return new Pair<>(topic, topicLine); }),
                        ActionUtil.createEmptyEvent()
                    );
                }
            }
        });

        topicLineList.setDragEnabled(true);
        RowsDnDSupport.install(topicLineList, topicLineListModel);

    }

    private static class MyDetailView extends DetailViewImpl
    {
        MyDetailView(Project project) {
            super(project);
        }

        @NotNull
        @Override
        protected Editor createEditor(@Nullable Project project, Document document, VirtualFile file) {
            Editor editor = super.createEditor(project, document, file);
            editor.setBorder(JBUI.Borders.empty());
            return editor;
        }
    }

    void clear()
    {
        memoArea.setText("");
        topicLineListModel.clear();
    }

    void setTopic(Topic topic)
    {
        this.topic = topic;

//        if (topic.memo().equals("")) {
//            memoArea.setPlaceholder("memo input area");
//        } else {
//            memoArea.setText(topic.memo());
//        }
        memoArea.setText(topic.memo());
        memoArea.setOneLineMode(false);

        topicLineListModel.clear();
        Iterator<TopicLine> iterator = topic.linesIterator();
        while (iterator.hasNext()) {
            topicLineListModel.addElement(iterator.next());
        }

        topicLineList.setModel(topicLineListModel);
    }

    private static class TopicLineListCellRenderer<T> extends JLabel implements ListCellRenderer<T>
    {
        private Project project;

        private TopicLineListCellRenderer(Project project)
        {
            this.project = project;
            setOpaque(true);
        }

        public Component getListCellRendererComponent(
            JList list,
            Object value,
            int index,
            boolean isSelected,
            boolean cellHasFocus)
        {
            TopicLine topicLine = (TopicLine) value;
            VirtualFile file = topicLine.file();

            PsiElement fileOrDir = PsiUtilCore.findFileSystemItem(project, file);
            if (fileOrDir != null) {
                setIcon(fileOrDir.getIcon(0));
            }

            setText(file.getName() + ":" + topicLine.line());

            setForeground(UIUtil.getListSelectionForeground(isSelected));
            setBackground(UIUtil.getListSelectionBackground(isSelected));
            return this;
        }
    }

    private static class TopicLineListModel<T> extends DefaultListModel<T> implements EditableModel
    {
        public void addRow() {}
        public void removeRow(int i) {}
        public boolean canExchangeRows(int oldIndex, int newIndex) { return true; }

        public void exchangeRows(int oldIndex, int newIndex)
        {
            TopicLine a = (TopicLine) get(oldIndex);
            a.setOrder(newIndex);

            TopicLine b = (TopicLine) get(newIndex);
            b.setOrder(oldIndex);

            set(newIndex, (T)a);
            set(oldIndex, (T)b);
        }
    }
}
