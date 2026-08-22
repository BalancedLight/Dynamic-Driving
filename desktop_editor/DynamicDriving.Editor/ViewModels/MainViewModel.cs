using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using DynamicDriving.Core.Audio;
using DynamicDriving.Core.Manifests;
using DynamicDriving.Core.Playback;

namespace DynamicDriving.Editor.ViewModels;

/// <summary>Everything the view model needs from the windowing layer.</summary>
public interface IUserInteraction
{
    string? PickFolder(string title);

    string? PickFile(string title, string filter);

    bool ConfirmDiscardChanges();

    bool Confirm(string message, string title);

    void ShowError(string message, string title);

    void ShowInformation(string message, string title);
}

/// <summary>
/// The editor's single view model: the open folder, the manifest being edited, the preview
/// transport, and everything the three panes bind to.
/// </summary>
public partial class MainViewModel : ObservableObject, IDisposable
{
    private readonly IUserInteraction _interaction;
    private readonly StemPlaybackEngine _engine = new();

    private SongManifest _manifest = new();
    private bool _suppressDirty;

    public MainViewModel(IUserInteraction interaction)
    {
        _interaction = interaction;
        Stems.CollectionChanged += OnStemsCollectionChanged;
        PropertyChanged += OnAnyPropertyChanged;
    }

    // ------------------------------------------------------------------ song fields

    [ObservableProperty]
    private string? _folderPath;

    [ObservableProperty]
    private string _songId = string.Empty;

    [ObservableProperty]
    private string _displayName = string.Empty;

    [ObservableProperty]
    private string _artist = string.Empty;

    [ObservableProperty]
    private string _album = string.Empty;

    [ObservableProperty]
    private string _transportStemId = string.Empty;

    [ObservableProperty]
    private long _loopStartMs;

    [ObservableProperty]
    private long _loopEndMs = 10_000;

    [ObservableProperty]
    private bool _playTailOverLoop;

    [ObservableProperty]
    private bool _loopStartsSong = true;

    [ObservableProperty]
    private bool _muffleEnabled;

    [ObservableProperty]
    private double _muffleReleaseMph = 1.0;

    [ObservableProperty]
    private double _muffleWetMix = 0.85;

    [ObservableProperty]
    private double _muffleCutoffHz = 300.0;

    [ObservableProperty]
    private long _muffleFadeMs = 1_200;

    [ObservableProperty]
    private string? _coverPath;

    public ObservableCollection<StemViewModel> Stems { get; } = new();

    [ObservableProperty]
    private StemViewModel? _selectedStem;

    [ObservableProperty]
    private StemEventViewModel? _selectedEvent;

    // ------------------------------------------------------------------ transport and preview

    [ObservableProperty]
    private double _speedMph;

    [ObservableProperty]
    private double _positionMs;

    [ObservableProperty]
    private double _totalMs;

    [ObservableProperty]
    private bool _isPlaying;

    [ObservableProperty]
    private int _loopCount;

    [ObservableProperty]
    private Waveform _waveform = Waveform.Empty;

    [ObservableProperty]
    private string _statusMessage = "Open a song folder to begin.";

    [ObservableProperty]
    private bool _isDirty;

    public ObservableCollection<ValidationIssue> Issues { get; } = new();

    public static IReadOnlyList<double> SpeedPresets { get; } = new[] { 0.0, 5.0, 15.0, 25.0, 35.0, 55.0, 70.0, 100.0 };

    public string WindowTitle =>
        FolderPath is null
            ? "Dynamic Driving Editor"
            : $"Dynamic Driving Editor — {Path.GetFileName(FolderPath.TrimEnd(Path.DirectorySeparatorChar))}{(IsDirty ? " *" : string.Empty)}";

    // ------------------------------------------------------------------ commands

    [RelayCommand]
    private void OpenFolder()
    {
        if (!ConfirmDiscardIfDirty())
        {
            return;
        }

        var folder = _interaction.PickFolder("Choose a song folder");
        if (folder is null)
        {
            return;
        }

        LoadFolder(folder);
    }

    [RelayCommand]
    private void NewSong()
    {
        if (!ConfirmDiscardIfDirty())
        {
            return;
        }

        var folder = _interaction.PickFolder("Choose a folder for the new song");
        if (folder is null)
        {
            return;
        }

        var wavFiles = SongFolder.ScanWavFiles(folder);
        if (wavFiles.Count == 0)
        {
            _interaction.ShowError(
                "That folder holds no .wav files. Put the song's stems in it first, then create the song.",
                "No stems found");
            return;
        }

        ApplyManifest(SongFolder.CreateDefaultManifest(folder, wavFiles), folder, wavFiles);
        MarkDirty();
        StatusMessage = $"Started a new song from {wavFiles.Count} stem file(s). Nothing has been saved yet.";
    }

    [RelayCommand]
    private void ReloadFolder()
    {
        if (FolderPath is null || !ConfirmDiscardIfDirty())
        {
            return;
        }

        LoadFolder(FolderPath);
    }

    [RelayCommand(CanExecute = nameof(HasFolder))]
    private void Save()
    {
        if (FolderPath is null)
        {
            return;
        }

        SaveTo(FolderPath);
    }

    [RelayCommand(CanExecute = nameof(HasFolder))]
    private void SaveCopy()
    {
        var folder = _interaction.PickFolder("Choose a folder to save a copy into");
        if (folder is null)
        {
            return;
        }

        if (!string.Equals(Path.GetFullPath(folder), Path.GetFullPath(FolderPath!), StringComparison.OrdinalIgnoreCase))
        {
            CopyStemsTo(folder);
        }

        SaveTo(folder, markClean: false);
    }

    [RelayCommand(CanExecute = nameof(HasFolder))]
    private void ImportCover()
    {
        var image = _interaction.PickFile("Choose cover art", "Images|*.png;*.jpg;*.jpeg;*.webp");
        if (image is null || FolderPath is null)
        {
            return;
        }

        try
        {
            CoverPath = SongFolder.ImportCover(FolderPath, image);
            StatusMessage = $"Cover art set to {Path.GetFileName(CoverPath)}.";
        }
        catch (Exception error) when (error is IOException or NotSupportedException or UnauthorizedAccessException)
        {
            _interaction.ShowError(error.Message, "Could not import cover art");
        }
    }

    [RelayCommand(CanExecute = nameof(HasFolder))]
    private void TogglePlayback()
    {
        if (_engine.IsPlaying)
        {
            _engine.Pause();
        }
        else
        {
            SyncEngineWithEdits();
            _engine.Play();
            if (_engine.DeviceError is { } deviceError)
            {
                StatusMessage = $"No audio device available: {deviceError}";
            }
        }

        IsPlaying = _engine.IsPlaying;
    }

    [RelayCommand(CanExecute = nameof(HasFolder))]
    private void StopPlayback()
    {
        _engine.Stop();
        IsPlaying = false;
        PositionMs = 0;
    }

    [RelayCommand(CanExecute = nameof(HasFolder))]
    private void PlayTail()
    {
        if (!_engine.PlayTailFromLoopEnd())
        {
            StatusMessage = "This song has no audio after the loop end, so there is no tail to play.";
        }
    }

    [RelayCommand(CanExecute = nameof(HasFolder))]
    private void ApplyLoopEdits()
    {
        SyncEngineWithEdits();
        Validate();
        StatusMessage = $"Loop set to {LoopStartMs} – {LoopEndMs} ms.";
    }

    [RelayCommand]
    private void SetSpeed(object? preset)
    {
        if (preset is double speed)
        {
            SpeedMph = speed;
        }
        else if (preset is string text && double.TryParse(text, out var parsed))
        {
            SpeedMph = parsed;
        }
    }

    [RelayCommand(CanExecute = nameof(HasSelectedStem))]
    private void AddEvent()
    {
        var stem = SelectedStem;
        if (stem is null)
        {
            return;
        }

        var stemEvent = StemEventViewModel.CreateNew();
        stem.Events.Add(stemEvent);
        SelectedEvent = stemEvent;
        MarkDirty();
        StatusMessage = $"Added event '{stemEvent.DisplayName}'.";
    }

    [RelayCommand]
    private void ToggleStemPreview(StemViewModel? stem)
    {
        if (stem is null)
        {
            return;
        }

        if (!stem.Included)
        {
            stem.Included = true;
            stem.IsMuted = false;
            return;
        }

        stem.IsMuted = !stem.IsMuted;
    }

    [RelayCommand]
    private void RemoveEvent(StemEventViewModel? stemEvent)
    {
        if (stemEvent is null || SelectedStem is null)
        {
            return;
        }

        SelectedStem.Events.Remove(stemEvent);
        if (ReferenceEquals(SelectedEvent, stemEvent))
        {
            SelectedEvent = SelectedStem.Events.FirstOrDefault();
        }

        MarkDirty();
    }

    [RelayCommand(CanExecute = nameof(HasSelectedEvent))]
    private void AddCondition()
    {
        if (SelectedEvent is null)
        {
            return;
        }

        SelectedEvent.Conditions.Add(MphConditionViewModel.CreateNew());
        MarkDirty();
        StatusMessage = $"Added condition to '{SelectedEvent.DisplayName}'.";
    }

    [RelayCommand]
    private void RemoveCondition(MphConditionViewModel? condition)
    {
        if (condition is not null)
        {
            SelectedEvent?.Conditions.Remove(condition);
            MarkDirty();
        }
    }

    [RelayCommand(CanExecute = nameof(HasSelectedEvent))]
    private void AddGainModifier()
    {
        if (SelectedEvent is null)
        {
            return;
        }

        SelectedEvent.Modifiers.Add(StemModifierViewModel.CreateGain());
        MarkDirty();
        StatusMessage = $"Added gain modifier to '{SelectedEvent.DisplayName}'.";
    }

    [RelayCommand(CanExecute = nameof(HasSelectedEvent))]
    private void AddReverbModifier()
    {
        if (SelectedEvent is null)
        {
            return;
        }

        SelectedEvent.Modifiers.Add(StemModifierViewModel.CreateReverb());
        MarkDirty();
        StatusMessage = $"Added reverb modifier to '{SelectedEvent.DisplayName}'.";
    }

    [RelayCommand]
    private void RemoveModifier(StemModifierViewModel? modifier)
    {
        if (modifier is not null)
        {
            SelectedEvent?.Modifiers.Remove(modifier);
            MarkDirty();
        }
    }

    [RelayCommand(CanExecute = nameof(HasFolder))]
    private void RescanStems()
    {
        if (FolderPath is null)
        {
            return;
        }

        var wavFiles = SongFolder.ScanWavFiles(FolderPath);
        var known = Stems.Select(stem => stem.AssetPath).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var added = 0;

        foreach (var wav in wavFiles.Where(wav => !known.Contains(wav)))
        {
            Stems.Add(StemViewModel.ForUnusedFile(wav));
            added++;
        }

        if (added > 0)
        {
            ReloadEngine();
        }

        StatusMessage = added == 0
            ? "No new .wav files were found in the folder."
            : $"Found {added} new .wav file(s). Tick the ones this song should use.";
    }

    private bool HasFolder => FolderPath is not null;

    private bool HasSelectedStem => SelectedStem is not null;

    private bool HasSelectedEvent => SelectedEvent is not null;

    // ------------------------------------------------------------------ loading and saving

    public void LoadFolder(string folderPath)
    {
        try
        {
            var song = SongFolder.Open(folderPath);
            ApplyManifest(song.Manifest, folderPath, song.WavFiles);
            StatusMessage = song.ManifestExisted
                ? $"Opened {Path.GetFileName(folderPath.TrimEnd(Path.DirectorySeparatorChar))}."
                : "No song.json in that folder yet; a starting manifest was created from the stems.";
        }
        catch (Exception error) when (error is SongManifestFormatException or IOException or DirectoryNotFoundException)
        {
            _interaction.ShowError(error.Message, "Could not open that folder");
        }
    }

    private void ApplyManifest(SongManifest manifest, string folderPath, IReadOnlyList<string> wavFiles)
    {
        _suppressDirty = true;
        try
        {
            _manifest = manifest;
            FolderPath = folderPath;
            SongId = manifest.SongId;
            DisplayName = manifest.DisplayName;
            Artist = manifest.Artist ?? string.Empty;
            Album = manifest.Album ?? string.Empty;
            TransportStemId = manifest.TransportStemId;
            LoopStartMs = manifest.LoopRegion.StartMs;
            LoopEndMs = manifest.LoopRegion.EndMs;
            PlayTailOverLoop = manifest.LoopRegion.PlayTailOverLoop;
            LoopStartsSong = manifest.LoopRegion.LoopStartsSong;

            MuffleEnabled = manifest.Muffle is not null;
            if (manifest.Muffle is { } muffle)
            {
                MuffleReleaseMph = muffle.ReleaseMph;
                MuffleWetMix = muffle.WetMix;
                MuffleCutoffHz = muffle.CutoffHz;
                MuffleFadeMs = muffle.FadeMs;
            }

            Stems.Clear();
            foreach (var stem in manifest.Stems)
            {
                Stems.Add(new StemViewModel(stem));
            }

            var known = Stems.Select(stem => stem.AssetPath).ToHashSet(StringComparer.OrdinalIgnoreCase);
            foreach (var wav in wavFiles.Where(wav => !known.Contains(wav)))
            {
                Stems.Add(StemViewModel.ForUnusedFile(wav));
            }

            SelectedStem = Stems.FirstOrDefault(stem => stem.Included);
            SelectedEvent = SelectedStem?.Events.FirstOrDefault();
            CoverPath = SongFolder.Open(folderPath).CoverPath;

            ReloadEngine();
            LoadWaveform();
            Validate();
            IsDirty = false;
        }
        finally
        {
            _suppressDirty = false;
        }
    }

    private void SaveTo(string folderPath, bool markClean = true)
    {
        var wasSuppressingDirty = _suppressDirty;
        try
        {
            // Serializing and validating normalize the backing manifest. Treat that whole operation
            // as part of Save so notifications raised by normalization cannot immediately put the
            // asterisk back after a successful write.
            _suppressDirty = true;
            var manifest = BuildManifest();
            SongFolder.Save(manifest, folderPath);
            Validate();
            StatusMessage = $"Saved song.json to {folderPath}.";

            if (markClean)
            {
                IsDirty = false;
            }
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or UnauthorizedAccessException
                                          or SongManifestFormatException)
        {
            _interaction.ShowError(
                $"{error.Message}{Environment.NewLine}{Environment.NewLine}The song.json already on disk was left untouched.",
                "Could not save");
        }
        finally
        {
            _suppressDirty = wasSuppressingDirty;
        }
    }

    private void CopyStemsTo(string targetFolder)
    {
        if (FolderPath is null)
        {
            return;
        }

        foreach (var stem in Stems.Where(stem => stem.Included))
        {
            var source = Path.Combine(FolderPath, stem.AssetPath.Replace('/', Path.DirectorySeparatorChar));
            var target = Path.Combine(targetFolder, stem.AssetPath.Replace('/', Path.DirectorySeparatorChar));
            if (!File.Exists(source) || File.Exists(target))
            {
                continue;
            }

            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(source, target);
        }

        if (CoverPath is not null && File.Exists(CoverPath))
        {
            var coverTarget = Path.Combine(targetFolder, Path.GetFileName(CoverPath));
            if (!File.Exists(coverTarget))
            {
                File.Copy(CoverPath, coverTarget);
            }
        }
    }

    /// <summary>Rebuilds the manifest from the edited fields, preserving unknown JSON properties.</summary>
    public SongManifest BuildManifest()
    {
        _manifest.SongId = SongId.Trim();
        _manifest.DisplayName = DisplayName.Trim();
        _manifest.Artist = string.IsNullOrWhiteSpace(Artist) ? null : Artist.Trim();
        _manifest.Album = string.IsNullOrWhiteSpace(Album) ? null : Album.Trim();
        _manifest.TransportStemId = TransportStemId.Trim();

        _manifest.LoopRegion.StartMs = LoopStartMs;
        _manifest.LoopRegion.EndMs = LoopEndMs;
        _manifest.LoopRegion.PlayTailOverLoop = PlayTailOverLoop;
        _manifest.LoopRegion.LoopStartsSong = LoopStartsSong;

        if (MuffleEnabled)
        {
            _manifest.Muffle ??= new MuffleSettings();
            _manifest.Muffle.ReleaseMph = MuffleReleaseMph;
            _manifest.Muffle.WetMix = MuffleWetMix;
            _manifest.Muffle.CutoffHz = MuffleCutoffHz;
            _manifest.Muffle.FadeMs = MuffleFadeMs;
        }
        else
        {
            _manifest.Muffle = null;
        }

        _manifest.Stems.Clear();
        foreach (var stem in Stems.Where(stem => stem.Included))
        {
            _manifest.Stems.Add(stem.ToManifest());
        }

        return _manifest;
    }

    public void Validate()
    {
        Issues.Clear();
        var manifest = BuildManifest();
        var report = SongManifestValidator.Validate(manifest, FolderPath);
        foreach (var issue in report.Issues)
        {
            Issues.Add(issue);
        }
    }

    // ------------------------------------------------------------------ preview plumbing

    private void ReloadEngine()
    {
        if (FolderPath is null)
        {
            return;
        }

        var failures = _engine.Load(
            BuildManifest(),
            FolderPath,
            browserStems: Stems.Select(stem => stem.ToManifest()).ToList());
        foreach (var stem in Stems.Where(stem => stem.IsMuted))
        {
            _engine.SetStemMuted(stem.StemId, muted: true);
        }

        TotalMs = _engine.TotalMs;
        _engine.SetSpeedMph(SpeedMph);

        if (failures.Count > 0)
        {
            StatusMessage = "Some stems could not be loaded: " +
                            string.Join("; ", failures.Select(entry => $"{entry.Key} ({entry.Value})"));
        }
    }

    private void SyncEngineWithEdits() => _engine.UpdateManifest(BuildManifest());

    private void LoadWaveform()
    {
        var transportStem = Stems.FirstOrDefault(stem => stem.Included && stem.StemId == TransportStemId)
                            ?? Stems.FirstOrDefault(stem => stem.Included);
        if (transportStem is null || FolderPath is null)
        {
            Waveform = Waveform.Empty;
            return;
        }

        var path = Path.Combine(FolderPath, transportStem.AssetPath.Replace('/', Path.DirectorySeparatorChar));
        try
        {
            Waveform = WaveformGenerator.Generate(WavFile.Read(path));
            TotalMs = Waveform.DurationMs;
        }
        catch (Exception error) when (error is WavFormatException or IOException)
        {
            Waveform = Waveform.Empty;
            StatusMessage = $"Could not read {transportStem.AssetPath}: {error.Message}";
        }
    }

    /// <summary>Called on a UI timer to move the playhead and refresh the live stem readouts.</summary>
    public void RefreshPreviewState()
    {
        if (!_engine.HasSong)
        {
            return;
        }

        PositionMs = _engine.PositionMs;
        LoopCount = _engine.LoopCount;
        IsPlaying = _engine.IsPlaying;

        foreach (var live in _engine.SnapshotStems())
        {
            var stem = Stems.FirstOrDefault(candidate => candidate.StemId == live.StemId);
            if (stem is null)
            {
                continue;
            }

            stem.LiveGain = live.CurrentGain;
            stem.LiveEligible = live.Eligible;
            stem.LiveEvents = string.Join(", ", live.ActiveEvents);
        }
    }

    public void SeekTo(double positionMs)
    {
        _engine.Seek(positionMs);
        PositionMs = positionMs;
    }

    public bool ConfirmDiscardIfDirty() => !IsDirty || _interaction.ConfirmDiscardChanges();

    public void Dispose() => _engine.Dispose();

    // ------------------------------------------------------------------ change tracking

    private void OnAnyPropertyChanged(object? sender, PropertyChangedEventArgs args)
    {
        switch (args.PropertyName)
        {
            case nameof(FolderPath):
            case nameof(IsDirty):
                OnPropertyChanged(nameof(WindowTitle));
                SaveCommand.NotifyCanExecuteChanged();
                SaveCopyCommand.NotifyCanExecuteChanged();
                ImportCoverCommand.NotifyCanExecuteChanged();
                TogglePlaybackCommand.NotifyCanExecuteChanged();
                StopPlaybackCommand.NotifyCanExecuteChanged();
                PlayTailCommand.NotifyCanExecuteChanged();
                ApplyLoopEditsCommand.NotifyCanExecuteChanged();
                RescanStemsCommand.NotifyCanExecuteChanged();
                return;

            case nameof(SelectedStem):
                SelectedEvent = SelectedStem?.Events.FirstOrDefault();
                AddEventCommand.NotifyCanExecuteChanged();
                return;

            case nameof(SpeedMph):
                _engine.SetSpeedMph(SpeedMph);
                return;

            case nameof(SelectedEvent):
                AddConditionCommand.NotifyCanExecuteChanged();
                AddGainModifierCommand.NotifyCanExecuteChanged();
                AddReverbModifierCommand.NotifyCanExecuteChanged();
                return;

            // Purely presentational state must not mark the document dirty.
            case nameof(PositionMs):
            case nameof(TotalMs):
            case nameof(IsPlaying):
            case nameof(LoopCount):
            case nameof(StatusMessage):
            case nameof(Waveform):
            case nameof(CoverPath):
                return;
        }

        MarkDirty();
    }

    private void MarkDirty()
    {
        if (!_suppressDirty && FolderPath is not null)
        {
            IsDirty = true;
        }
    }

    private void OnStemsCollectionChanged(object? sender, NotifyCollectionChangedEventArgs args)
    {
        if (args.OldItems is not null)
        {
            foreach (StemViewModel stem in args.OldItems)
            {
                stem.Edited -= OnStemEdited;
                stem.InclusionChanged -= OnStemInclusionChanged;
                stem.PreviewMuteChanged -= OnStemPreviewMuteChanged;
            }
        }

        if (args.NewItems is not null)
        {
            foreach (StemViewModel stem in args.NewItems)
            {
                stem.Edited += OnStemEdited;
                stem.InclusionChanged += OnStemInclusionChanged;
                stem.PreviewMuteChanged += OnStemPreviewMuteChanged;
            }
        }

        MarkDirty();
    }

    private void OnStemEdited(object? sender, EventArgs args)
    {
        MarkDirty();
        if (!_suppressDirty && _engine.HasSong)
        {
            SyncEngineWithEdits();
        }
    }

    private void OnStemInclusionChanged(object? sender, EventArgs args)
    {
        if (_suppressDirty || sender is not StemViewModel stem)
        {
            return;
        }

        StatusMessage = stem.Included
            ? $"Fading '{stem.Label}' into the preview; it will be included when saved."
            : $"Fading '{stem.Label}' out of the preview; it will be omitted when saved.";
    }

    private void OnStemPreviewMuteChanged(object? sender, EventArgs args)
    {
        if (sender is not StemViewModel stem)
        {
            return;
        }

        _engine.SetStemMuted(stem.StemId, stem.IsMuted);
        StatusMessage = stem.IsMuted
            ? $"Fading '{stem.Label}' out of the preview. It remains included when saved."
            : $"Fading '{stem.Label}' back into the preview.";
    }
}
