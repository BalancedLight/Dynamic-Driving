using System.ComponentModel;
using System.Windows;
using System.Windows.Threading;
using DynamicDriving.Editor.ViewModels;
using Microsoft.Win32;

namespace DynamicDriving.Editor;

public partial class MainWindow : Window, IUserInteraction
{
    private readonly MainViewModel _viewModel;
    private readonly DispatcherTimer _previewTimer;

    public MainWindow()
    {
        InitializeComponent();

        _viewModel = new MainViewModel(this);
        DataContext = _viewModel;

        Waveform.SeekRequested += (_, positionMs) => _viewModel.SeekTo(positionMs);
        Waveform.LoopChanged += (_, loop) =>
        {
            _viewModel.LoopStartMs = loop.StartMs;
            _viewModel.LoopEndMs = loop.EndMs;
            _viewModel.ApplyLoopEditsCommand.Execute(null);
        };

        // 20 Hz is enough for a playhead that reads as smooth without competing with the mixer.
        _previewTimer = new DispatcherTimer(DispatcherPriority.Render)
        {
            Interval = TimeSpan.FromMilliseconds(50)
        };
        _previewTimer.Tick += (_, _) =>
        {
            _viewModel.RefreshPreviewState();
            Waveform.InvalidateVisual();
        };
        _previewTimer.Start();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_viewModel.ConfirmDiscardIfDirty())
        {
            e.Cancel = true;
            return;
        }

        _previewTimer.Stop();
        _viewModel.Dispose();
        base.OnClosing(e);
    }

    // ------------------------------------------------------------------ IUserInteraction

    public string? PickFolder(string title)
    {
        // WPF's own folder picker, so the published app carries no WinForms dependency.
        var dialog = new OpenFolderDialog
        {
            Title = title,
            Multiselect = false
        };

        return dialog.ShowDialog(this) == true ? dialog.FolderName : null;
    }

    public string? PickFile(string title, string filter)
    {
        var dialog = new OpenFileDialog
        {
            Title = title,
            Filter = filter,
            CheckFileExists = true
        };

        return dialog.ShowDialog(this) == true ? dialog.FileName : null;
    }

    public bool ConfirmDiscardChanges() => Confirm(
        "This song has unsaved changes. Discard them?",
        "Unsaved changes");

    public bool Confirm(string message, string title) =>
        MessageBox.Show(this, message, title, MessageBoxButton.OKCancel, MessageBoxImage.Question)
        == MessageBoxResult.OK;

    public void ShowError(string message, string title) =>
        MessageBox.Show(this, message, title, MessageBoxButton.OK, MessageBoxImage.Error);

    public void ShowInformation(string message, string title) =>
        MessageBox.Show(this, message, title, MessageBoxButton.OK, MessageBoxImage.Information);
}
