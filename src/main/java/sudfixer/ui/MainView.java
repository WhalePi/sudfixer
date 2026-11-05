package sudfixer.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import sudfixer.core.FilenameTime;
import sudfixer.core.FixJob;
import sudfixer.core.SudTimeShifter;
import sudfixer.core.TimeOffset;

/** The single window: choose files, type an offset, choose where the results go, process. */
public class MainView extends BorderPane {

    private static final DateTimeFormatter UTC_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Stage stage;

    // ----- files
    private final ObservableList<Path> files = FXCollections.observableArrayList();
    private final ListView<Path> fileList = new ListView<>(files);
    private final CheckBox subFolders = new CheckBox("Include sub folders");
    private final Label sourceLabel = new Label("No files selected.");
    private final Label fileSummary = new Label("0 files");
    private Path sourceFolder;

    // ----- offset
    private final TextField offsetField = new TextField();
    private final Label offsetParsed = new Label();
    private final CheckBox renameFiles = new CheckBox("Change the time in file names");
    private final CheckBox shiftModified = new CheckBox("Shift the file modified date");
    private final Label previewFile = new Label("–");
    private final Label previewOldTime = new Label("–");
    private final Label previewNewTime = new Label("–");
    private final Label previewOldName = new Label("–");
    private final Label previewNewName = new Label("–");
    private final Label previewOldModified = new Label("–");
    private final Label previewNewModified = new Label("–");
    private Path previewPath;
    private Long previewMicros;
    private FileTime previewModified;

    // ----- output
    private final RadioButton overwrite = new RadioButton("Overwrite the original files");
    private final RadioButton newFolder = new RadioButton("Save to a new folder");
    private final TextField outputField = new TextField();
    private final Button browseOutput = new Button("Browse…");

    // ----- run
    private final Button processButton = new Button("Process");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Select a folder or some .sud files to start.");

    private Task<?> runningTask;
    /** Set while the list is refreshed after processing, so the result stays in the status bar. */
    private boolean keepStatus;

    public MainView(Stage stage) {
        this.stage = stage;
        getStyleClass().add("root");

        setTop(buildHeader());
        setCenter(buildBody());
        setBottom(buildStatusBar());

        refreshPreview();
        refreshControls();
    }

    /** Stop any running work, e.g. when the window closes. */
    public void cancel() {
        if (runningTask != null) {
            runningTask.cancel();
        }
    }

    // ------------------------------------------------------------------ header

    private Node buildHeader() {
        ImageView logo = new ImageView();
        var url = MainView.class.getResource("/sudfixer/sudfixer_icon.png");
        if (url != null) {
            logo.setImage(new Image(url.toExternalForm(), 128, 128, true, true));
            logo.setFitWidth(42);
            logo.setFitHeight(42);
            logo.setPreserveRatio(true);
            logo.setSmooth(true);
        }

        Label title = new Label("SUD Time Fixer");
        title.getStyleClass().add("header-title");
        Label subtitle = new Label("Shift the recorded time of SoundTrap .sud files");
        subtitle.getStyleClass().add("header-subtitle");
        VBox text = new VBox(1, title, subtitle);
        text.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(14, logo, text);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        return header;
    }

    // -------------------------------------------------------------------- body

    private Node buildBody() {
        Node filesPanel = buildFilesPanel();
        VBox body = new VBox(14, filesPanel, buildOffsetPanel(), buildOutputPanel(), buildRunRow());
        body.setPadding(new Insets(16));
        VBox.setVgrow(filesPanel, Priority.ALWAYS);
        return body;
    }

    private Node buildFilesPanel() {
        Button folderButton = new Button("Select folder…");
        folderButton.setGraphic(Icons.of(Icons.FOLDER_OPEN, 15, Palette.PRIMARY));
        folderButton.setOnAction(e -> chooseFolder());

        Button filesButton = new Button("Select files…");
        filesButton.setGraphic(Icons.of(Icons.FILE, 15, Palette.PRIMARY));
        filesButton.setOnAction(e -> chooseFiles());

        subFolders.setSelected(true);
        subFolders.selectedProperty().addListener((obs, was, is) -> {
            if (sourceFolder != null) scanFolder(sourceFolder);
        });

        Button clear = quietButton("Clear", Icons.CLEAR);
        clear.setOnAction(e -> {
            sourceFolder = null;
            setFiles(List.of(), "No files selected.");
        });

        for (Region r : List.of(folderButton, filesButton, subFolders)) {
            r.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox row = new HBox(10, folderButton, filesButton, subFolders, spacer(), clear);
        row.setAlignment(Pos.CENTER_LEFT);

        sourceLabel.getStyleClass().add("path-label");
        sourceLabel.setWrapText(true);

        fileList.setPlaceholder(new Label("No .sud files"));
        fileList.setPrefHeight(120);
        fileList.setMinHeight(80);
        fileList.setCellFactory(list -> new ListCell<>() {
            {
                // Size cells to the list, not the text, so long names ellipsise instead of scrolling.
                setPrefWidth(0);
                // Keep the end of long names visible: that is where the time is.
                setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
            }

            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(displayName(item));
                    setGraphic(Icons.of(Icons.GRAPHIC_EQ, 12, Palette.PRIMARY));
                }
            }
        });
        VBox.setVgrow(fileList, Priority.ALWAYS);

        fileSummary.getStyleClass().add("summary-value");
        HBox summary = new HBox(6, dim("Selected:"), fileSummary);
        summary.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10, row, sourceLabel, fileList, summary);
        return panel(1, "SUD files", Icons.FOLDER, content);
    }

    private Node buildOffsetPanel() {
        offsetField.setPromptText("+01:00:00");
        offsetField.getStyleClass().add("offset-field");
        offsetField.setPrefColumnCount(12);
        offsetField.setMinWidth(Region.USE_PREF_SIZE);
        offsetField.textProperty().addListener((obs, was, is) -> {
            refreshPreview();
            refreshControls();
        });

        Label hint = new Label("e.g.  +01:00:00   -00:30   1d 2h 15m   -3600   0.25s");
        hint.getStyleClass().add("panel-hint");

        Label offsetLabel = new Label("Offset:");
        offsetLabel.setMinWidth(Region.USE_PREF_SIZE);
        offsetParsed.setWrapText(true);
        HBox row = new HBox(10, offsetLabel, offsetField, offsetParsed);
        row.setAlignment(Pos.CENTER_LEFT);

        renameFiles.setSelected(true);
        shiftModified.setSelected(true);
        renameFiles.selectedProperty().addListener((obs, was, is) -> refreshPreview());
        shiftModified.selectedProperty().addListener((obs, was, is) -> refreshPreview());
        HBox options = new HBox(22, renameFiles, shiftModified);
        options.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        ColumnConstraints keyCol = new ColumnConstraints();
        keyCol.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(keyCol, valueCol);

        previewFile.getStyleClass().add("summary-label");
        grid.add(previewKey("First file"), 0, 0);
        grid.add(previewFile, 1, 0);
        addPreviewRow(grid, 1, "Recording start", previewOldTime, previewNewTime);
        addPreviewRow(grid, 2, "File name", previewOldName, previewNewName);
        addPreviewRow(grid, 3, "Modified", previewOldModified, previewNewModified);

        VBox preview = new VBox(grid);
        preview.getStyleClass().add("preview-box");

        VBox content = new VBox(10, row, hint, options, preview);
        return panel(2, "Time offset", Icons.SCHEDULE, content);
    }

    private void addPreviewRow(GridPane grid, int row, String key, Label old, Label now) {
        old.getStyleClass().add("preview-old");
        now.getStyleClass().add("preview-new");
        Label arrow = new Label("→");
        arrow.getStyleClass().add("preview-key");
        VBox values = new VBox(2, old, new HBox(6, arrow, now));
        grid.add(previewKey(key), 0, row);
        grid.add(values, 1, row);
    }

    private static Label previewKey(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("preview-key");
        return l;
    }

    private Node buildOutputPanel() {
        ToggleGroup group = new ToggleGroup();
        overwrite.setToggleGroup(group);
        newFolder.setToggleGroup(group);
        newFolder.setSelected(true);
        group.selectedToggleProperty().addListener((obs, was, is) -> refreshControls());

        outputField.setPromptText("Folder for the corrected files");
        outputField.textProperty().addListener((obs, was, is) -> refreshControls());
        HBox.setHgrow(outputField, Priority.ALWAYS);
        browseOutput.getStyleClass().add("button-quiet");
        browseOutput.setGraphic(Icons.of(Icons.FOLDER, 13, Palette.TEXT_DIM));
        browseOutput.setMinWidth(Region.USE_PREF_SIZE);
        browseOutput.setOnAction(e -> chooseOutput());
        matchHeight(browseOutput, outputField);

        HBox folderRow = new HBox(10, outputField, browseOutput);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        folderRow.setPadding(new Insets(0, 0, 0, 26));

        HBox radios = new HBox(22, newFolder, overwrite);
        radios.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, radios, folderRow);
        return panel(3, "Save", Icons.SAVE, content);
    }

    /** The Process button, with the progress bar beside it. */
    private Node buildRunRow() {
        progressBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        processButton.getStyleClass().add("button-primary");
        processButton.setMinWidth(Region.USE_PREF_SIZE);
        processButton.setOnAction(e -> {
            if (runningTask != null) {
                runningTask.cancel();
            } else {
                startProcess();
            }
        });

        HBox row = new HBox(14, progressBar, processButton);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("run-row");
        return row;
    }

    private Node buildStatusBar() {
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox bar = new HBox(statusLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // --------------------------------------------------------------- choosing

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Folder of .sud files");
        if (sourceFolder != null && Files.isDirectory(sourceFolder)) {
            chooser.setInitialDirectory(sourceFolder.toFile());
        }
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            sourceFolder = dir.toPath();
            scanFolder(sourceFolder);
        }
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select .sud files");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SoundTrap SUD files", "*.sud", "*.SUD"));
        if (!files.isEmpty() && files.get(0).getParent() != null) {
            chooser.setInitialDirectory(files.get(0).getParent().toFile());
        }
        List<File> chosen = chooser.showOpenMultipleDialog(stage);
        if (chosen != null && !chosen.isEmpty()) {
            sourceFolder = null;
            List<Path> paths = new ArrayList<>();
            for (File f : chosen) paths.add(f.toPath());
            paths.sort(Comparator.comparing(p -> p.getFileName().toString()));
            Path parent = paths.get(0).getParent();
            setFiles(paths, paths.size() == 1 ? paths.get(0).toString() : parent + "  (" + paths.size() + " files)");
        }
    }

    private void chooseOutput() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Save corrected files to");
        File current = outputField.getText().isBlank() ? null : new File(outputField.getText());
        if (current != null && current.isDirectory()) chooser.setInitialDirectory(current);
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            outputField.setText(dir.getAbsolutePath());
            newFolder.setSelected(true);
        }
    }

    private void scanFolder(Path folder) {
        boolean deep = subFolders.isSelected();
        if (!keepStatus) status("Looking for .sud files in " + folder + "…", "status-busy");
        Task<List<Path>> scan = new Task<>() {
            @Override
            protected List<Path> call() throws IOException {
                try (Stream<Path> s = Files.walk(folder, deep ? Integer.MAX_VALUE : 1)) {
                    return s.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sud"))
                            .sorted(Comparator.comparing((Path p) -> folder.relativize(p).toString()))
                            .toList();
                }
            }
        };
        scan.setOnSucceeded(e -> {
            if (folder.equals(sourceFolder)) {
                setFiles(scan.getValue(), folder.toString() + (deep ? "  (and sub folders)" : ""));
            }
        });
        scan.setOnFailed(e -> {
            keepStatus = false;
            status("Could not read " + folder + ": " + scan.getException().getMessage(), "status-error");
        });
        Thread t = new Thread(scan, "scan");
        t.setDaemon(true);
        t.start();
    }

    private void setFiles(List<Path> paths, String source) {
        files.setAll(paths);
        sourceLabel.setText(source);
        long bytes = 0;
        for (Path p : paths) {
            try {
                bytes += Files.size(p);
            } catch (IOException e) {
                // ignore in the summary
            }
        }
        fileSummary.setText(paths.size() + (paths.size() == 1 ? " file, " : " files, ") + formatSize(bytes));

        if (sourceFolder != null && outputField.getText().isBlank() && !paths.isEmpty()) {
            Path parent = sourceFolder.getParent() == null ? sourceFolder : sourceFolder.getParent();
            outputField.setText(parent.resolve(sourceFolder.getFileName() + "_timefixed").toString());
        }

        if (keepStatus) {
            keepStatus = false;
        } else if (paths.isEmpty()) {
            status(sourceFolder != null ? "No .sud files found in " + sourceFolder : "Select a folder or some .sud files to start.",
                    sourceFolder != null ? "status-error" : null);
        } else {
            status("Found " + paths.size() + " .sud " + (paths.size() == 1 ? "file" : "files") + ". Type an offset.", "status-ok");
        }
        loadPreviewFile();
        refreshControls();
    }

    private String displayName(Path p) {
        if (sourceFolder != null && p.startsWith(sourceFolder)) {
            return sourceFolder.relativize(p).toString();
        }
        return p.getFileName().toString();
    }

    // ---------------------------------------------------------------- preview

    private void loadPreviewFile() {
        previewPath = files.isEmpty() ? null : files.get(0);
        previewMicros = null;
        previewModified = null;
        if (previewPath != null) {
            try {
                previewMicros = SudTimeShifter.firstDataTimeMicros(previewPath);
                previewModified = Files.getLastModifiedTime(previewPath);
            } catch (IOException e) {
                status("Could not read " + previewPath.getFileName() + ": " + e.getMessage(), "status-error");
            }
        }
        refreshPreview();
    }

    private TimeOffset currentOffset() {
        String text = offsetField.getText();
        if (text == null || text.isBlank()) return null;
        try {
            return TimeOffset.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void refreshPreview() {
        String text = offsetField.getText();
        TimeOffset offset = null;
        offsetField.getStyleClass().remove("offset-error");
        offsetParsed.getStyleClass().removeAll("status-error", "summary-value", "panel-hint");
        if (text == null || text.isBlank()) {
            offsetParsed.setText("Type the time to add (negative to subtract)");
            offsetParsed.getStyleClass().add("panel-hint");
        } else {
            try {
                offset = TimeOffset.parse(text);
                offsetParsed.setText("= " + offset.describe());
                offsetParsed.getStyleClass().add("summary-value");
            } catch (IllegalArgumentException e) {
                offsetParsed.setText(e.getMessage());
                offsetParsed.getStyleClass().add("status-error");
                offsetField.getStyleClass().add("offset-error");
            }
        }

        if (previewPath == null) {
            for (Label l : List.of(previewOldTime, previewNewTime, previewOldName, previewNewName,
                    previewOldModified, previewNewModified)) {
                l.setText("–");
            }
            previewFile.setText("No file selected");
            return;
        }

        previewFile.setText(displayName(previewPath));
        TimeOffset shown = offset == null ? TimeOffset.ZERO : offset;

        if (previewMicros != null) {
            previewOldTime.setText(formatMicros(previewMicros));
            previewNewTime.setText(offset == null ? "–" : formatMicros(previewMicros + shown.micros()));
        } else {
            previewOldTime.setText("no readable chunks");
            previewNewTime.setText("–");
        }

        String name = previewPath.getFileName().toString();
        previewOldName.setText(name);
        if (FilenameTime.parse(name) == null) {
            previewNewName.setText("unchanged (no time in the name)");
        } else if (!renameFiles.isSelected()) {
            previewNewName.setText("unchanged");
        } else {
            previewNewName.setText(offset == null ? "–" : FilenameTime.shift(name, shown));
        }

        if (previewModified != null) {
            previewOldModified.setText(LOCAL_FORMAT.format(previewModified.toInstant()) + " (local)");
            if (!shiftModified.isSelected()) {
                previewNewModified.setText("unchanged");
            } else {
                previewNewModified.setText(offset == null ? "–"
                        : LOCAL_FORMAT.format(Instant.ofEpochMilli(previewModified.toMillis() + shown.millis())) + " (local)");
            }
        }
    }

    private static String formatMicros(long micros) {
        Instant t = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
        return UTC_FORMAT.format(t);
    }

    // ---------------------------------------------------------------- process

    private void refreshControls() {
        boolean running = runningTask != null;
        outputField.setDisable(running || !newFolder.isSelected());
        browseOutput.setDisable(running || !newFolder.isSelected());

        if (running) {
            processButton.setText("Cancel");
            processButton.setGraphic(Icons.of(Icons.STOP, 14, Palette.BACKGROUND));
            processButton.setDisable(false);
            return;
        }
        processButton.setText("Process");
        processButton.setGraphic(Icons.of(Icons.PLAY, 15, Palette.BACKGROUND));
        TimeOffset offset = currentOffset();
        boolean ready = !files.isEmpty() && offset != null && !offset.isZero()
                && (overwrite.isSelected() || !outputField.getText().isBlank());
        processButton.setDisable(!ready);
    }

    private void startProcess() {
        TimeOffset offset = currentOffset();
        if (offset == null || files.isEmpty()) return;

        Path output = null;
        if (newFolder.isSelected()) {
            output = Path.of(outputField.getText().trim()).toAbsolutePath().normalize();
        } else {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "This rewrites " + files.size() + (files.size() == 1 ? " file" : " files")
                            + " in place, shifting " + (files.size() == 1 ? "its" : "their") + " time by "
                            + offset.describe() + ".\n\nThe originals cannot be recovered afterwards.",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setHeaderText("Overwrite the original files?");
            styleDialog(confirm);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }

        FixJob.Options options = new FixJob.Options(offset, renameFiles.isSelected(), shiftModified.isSelected(),
                output, sourceFolder);
        FixJob job = new FixJob(new ArrayList<>(files), options);

        Task<FixJob.Outcome> task = new Task<>() {
            @Override
            protected FixJob.Outcome call() {
                return job.run(new FixJob.Listener() {
                    @Override
                    public void file(int index, int count, Path source) {
                        updateMessage("Processing " + (index + 1) + " of " + count + ": " + source.getFileName());
                    }

                    @Override
                    public void bytes(long done, long total) {
                        updateProgress(done, Math.max(total, 1));
                    }
                }, this::isCancelled);
            }
        };

        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.getStyleClass().removeAll("status-ok", "status-error", "status-busy");
        statusLabel.getStyleClass().add("status-busy");
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> finished(task.getValue(), offset, options));
        task.setOnCancelled(e -> {
            // The job checks for cancellation between chunks and tidies up its temporary files.
            finishedTask();
            status("Cancelled.", "status-error");
        });
        task.setOnFailed(e -> {
            finishedTask();
            status("Failed: " + task.getException().getMessage(), "status-error");
        });

        runningTask = task;
        refreshControls();
        Thread t = new Thread(task, "sudfixer");
        t.setDaemon(true);
        t.start();
    }

    private void finishedTask() {
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();
        runningTask = null;
        refreshControls();
    }

    private void finished(FixJob.Outcome outcome, TimeOffset offset, FixJob.Options options) {
        finishedTask();
        progressBar.setProgress(1);
        String what = outcome.succeeded() + (outcome.succeeded() == 1 ? " file" : " files") + " shifted by " + offset.describe();
        if (!options.inPlace()) what += " into " + options.outputDir();
        if (outcome.errors().isEmpty()) {
            status(what + ".", "status-ok");
        } else {
            status(what + "; " + outcome.errors().size() + " failed.", "status-error");
            TextArea area = new TextArea(String.join("\n", outcome.errors()));
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(10);
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setHeaderText(outcome.errors().size() + " of " + (outcome.succeeded() + outcome.errors().size())
                    + " files could not be processed");
            alert.getDialogPane().setContent(area);
            styleDialog(alert);
            alert.show();
        }
        // In place, the files on disk now have new names and times.
        if (options.inPlace()) {
            keepStatus = true;
            if (sourceFolder != null) {
                scanFolder(sourceFolder);
            } else {
                List<Path> now = new ArrayList<>();
                for (FixJob.Item item : FixJob.plan(new ArrayList<>(files), options)) {
                    now.add(Files.exists(item.target()) ? item.target() : item.source());
                }
                setFiles(now, sourceLabel.getText());
            }
            offsetField.clear();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void styleDialog(Alert alert) {
        alert.initOwner(stage);
        var css = MainView.class.getResource("/sudfixer/sudfixer.css");
        if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
    }

    private void status(String text, String styleClass) {
        statusLabel.getStyleClass().removeAll("status-ok", "status-error", "status-busy");
        if (styleClass != null) statusLabel.getStyleClass().add(styleClass);
        statusLabel.setText(text);
    }

    private Node panel(int step, String title, String iconPath, Node body) {
        Label number = new Label(String.valueOf(step));
        number.getStyleClass().add("step-number");

        Label label = new Label(title);
        label.getStyleClass().add("panel-title");

        HBox head = new HBox(10, number, Icons.of(iconPath, 16, Palette.PRIMARY), label);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("panel-header");

        VBox content = new VBox(body);
        content.getStyleClass().add("panel-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox panel = new VBox(head, content);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(content, Priority.ALWAYS);
        return panel;
    }

    private Button quietButton(String text, String iconPath) {
        Button button = new Button(text);
        button.getStyleClass().add("button-quiet");
        button.setGraphic(Icons.of(iconPath, 13, Palette.TEXT_DIM));
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static void matchHeight(Region button, Region neighbour) {
        button.prefHeightProperty().bind(neighbour.heightProperty());
        button.setMinHeight(Region.USE_PREF_SIZE);
        button.setMaxHeight(Region.USE_PREF_SIZE);
    }

    private static Label dim(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("summary-label");
        return label;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }
}
