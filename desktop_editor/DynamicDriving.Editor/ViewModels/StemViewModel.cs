using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;
using System.Globalization;
using CommunityToolkit.Mvvm.ComponentModel;
using DynamicDriving.Core.Manifests;

namespace DynamicDriving.Editor.ViewModels;

/// <summary>
/// One stem, as edited.
///
/// The view model owns typed fields rather than raw JSON so the inspector can offer real controls,
/// and <see cref="ToManifest"/> rebuilds a manifest node that keeps the unknown properties the file
/// arrived with.
/// </summary>
public partial class StemViewModel : ObservableObject
{
    private readonly StemManifest _source;

    public StemViewModel(StemManifest source)
    {
        _source = source;
        _stemId = source.StemId;
        _displayName = source.DisplayName;
        _assetPath = source.AssetPath;
        _playTailOverLoop = source.PlayTailOverLoop;
        _gain = source.Gain;
        _fadeInMs = source.FadeInMs;
        _fadeOutMs = source.FadeOutMs;
        _included = true;

        _isOverlay = source.Rule is OverlayStemRule;
        _minMph = source.Rule.MinMph;
        _maxMphExclusive = source.Rule.MaxMphExclusive;

        if (source.Rule is OverlayStemRule overlay)
        {
            _groupId = overlay.GroupId;
            _groupName = overlay.GroupName;
            _durationMs = overlay.DurationMs;
            _cooldownMinMs = overlay.CooldownMinMs;
            _cooldownMaxMs = overlay.CooldownMaxMs;
            _weight = overlay.Weight;
        }

        Events = new ObservableCollection<StemEventViewModel>(
            source.Events.Select(stemEvent => new StemEventViewModel(stemEvent)));
        Events.CollectionChanged += OnEventsCollectionChanged;
        foreach (var stemEvent in Events)
        {
            stemEvent.Edited += OnNestedEventEdited;
        }

        PropertyChanged += OnOwnPropertyChanged;
    }

    /// <summary>Raised for every authored change, including nested event edits.</summary>
    public event EventHandler? Edited;

    /// <summary>Raised when the browser checkbox changes preview/save inclusion.</summary>
    public event EventHandler? InclusionChanged;

    /// <summary>Raised when preview-only mute changes; it never changes the saved manifest.</summary>
    public event EventHandler? PreviewMuteChanged;

    /// <summary>A stem the folder holds a WAV for but the manifest does not (yet) include.</summary>
    public static StemViewModel ForUnusedFile(string assetPath) =>
        new(SongFolder.CreateDefaultStem(assetPath)) { Included = false };

    [ObservableProperty]
    private string _stemId;

    [ObservableProperty]
    private string _displayName;

    [ObservableProperty]
    private string _assetPath;

    /// <summary>Unticked stems stay in the browser but are left out of the saved manifest.</summary>
    [ObservableProperty]
    private bool _included;

    /// <summary>Preview-only mute. This stem remains included in the saved song.</summary>
    [ObservableProperty]
    private bool _isMuted;

    public string MuteButtonLabel => IsMuted ? "Unmute" : "Mute";

    [ObservableProperty]
    private bool _playTailOverLoop;

    [ObservableProperty]
    private double _gain;

    [ObservableProperty]
    private long _fadeInMs;

    [ObservableProperty]
    private long _fadeOutMs;

    [ObservableProperty]
    private bool _isOverlay;

    [ObservableProperty]
    private double? _minMph;

    [ObservableProperty]
    private double? _maxMphExclusive;

    [ObservableProperty]
    private string _groupId = string.Empty;

    [ObservableProperty]
    private string? _groupName;

    [ObservableProperty]
    private long _durationMs = 20_000;

    [ObservableProperty]
    private long _cooldownMinMs = 20_000;

    [ObservableProperty]
    private long _cooldownMaxMs = 40_000;

    [ObservableProperty]
    private int _weight = 1;

    // Live preview state, refreshed from the audio engine while the song is playing.

    [ObservableProperty]
    private double _liveGain;

    [ObservableProperty]
    private bool _liveEligible;

    [ObservableProperty]
    private string _liveEvents = string.Empty;

    public ObservableCollection<StemEventViewModel> Events { get; }

    public string Label => !string.IsNullOrWhiteSpace(DisplayName) ? DisplayName : StemId;

    public StemManifest ToManifest()
    {
        _source.StemId = StemId.Trim();
        _source.DisplayName = DisplayName.Trim();
        _source.AssetPath = AssetPath.Replace('\\', '/');
        _source.PlayTailOverLoop = PlayTailOverLoop;
        _source.Gain = Gain;
        _source.FadeInMs = FadeInMs;
        _source.FadeOutMs = FadeOutMs;

        if (IsOverlay)
        {
            var overlay = _source.Rule as OverlayStemRule ?? new OverlayStemRule();
            overlay.MinMph = MinMph ?? 0.0;
            overlay.MaxMphExclusive = MaxMphExclusive;
            overlay.GroupId = string.IsNullOrWhiteSpace(GroupId) ? "overlay" : GroupId.Trim();
            overlay.GroupName = string.IsNullOrWhiteSpace(GroupName) ? null : GroupName!.Trim();
            overlay.DurationMs = DurationMs;
            overlay.CooldownMinMs = CooldownMinMs;
            overlay.CooldownMaxMs = CooldownMaxMs;
            overlay.Weight = Math.Max(1, Weight);
            _source.Rule = overlay;
        }
        else
        {
            var baseRule = _source.Rule as BaseStemRule ?? new BaseStemRule();
            baseRule.MinMph = MinMph;
            baseRule.MaxMphExclusive = MaxMphExclusive;
            _source.Rule = baseRule;
        }

        _source.Events.Clear();
        foreach (var stemEvent in Events)
        {
            _source.Events.Add(stemEvent.ToManifest());
        }

        return _source;
    }

    partial void OnDisplayNameChanged(string value) => OnPropertyChanged(nameof(Label));

    partial void OnStemIdChanged(string value) => OnPropertyChanged(nameof(Label));

    private void OnOwnPropertyChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (args.PropertyName is nameof(LiveGain) or nameof(LiveEligible) or nameof(LiveEvents) or nameof(Label)
            or nameof(MuteButtonLabel))
        {
            return;
        }

        if (args.PropertyName == nameof(IsMuted))
        {
            OnPropertyChanged(nameof(MuteButtonLabel));
            PreviewMuteChanged?.Invoke(this, EventArgs.Empty);
            return;
        }

        if (args.PropertyName == nameof(Included))
        {
            InclusionChanged?.Invoke(this, EventArgs.Empty);
        }

        Edited?.Invoke(this, EventArgs.Empty);
    }

    private void OnEventsCollectionChanged(object? sender, NotifyCollectionChangedEventArgs args)
    {
        if (args.OldItems is not null)
        {
            foreach (StemEventViewModel stemEvent in args.OldItems)
            {
                stemEvent.Edited -= OnNestedEventEdited;
            }
        }

        if (args.NewItems is not null)
        {
            foreach (StemEventViewModel stemEvent in args.NewItems)
            {
                stemEvent.Edited += OnNestedEventEdited;
            }
        }

        Edited?.Invoke(this, EventArgs.Empty);
    }

    private void OnNestedEventEdited(object? sender, EventArgs args) => Edited?.Invoke(this, EventArgs.Empty);
}

public partial class StemEventViewModel : ObservableObject
{
    private readonly StemEvent _source;

    public StemEventViewModel(StemEvent source)
    {
        _source = source;
        _eventId = source.EventId ?? "event";
        _displayName = source.DisplayName ?? string.Empty;
        Conditions = new ObservableCollection<MphConditionViewModel>(Flatten(source.Condition));
        _requiresAllConditions = source.Condition is not AnyCondition;
        Modifiers = new ObservableCollection<StemModifierViewModel>(
            source.Modifiers.Select(modifier => new StemModifierViewModel(modifier)));

        Conditions.CollectionChanged += OnConditionsCollectionChanged;
        Modifiers.CollectionChanged += OnModifiersCollectionChanged;
        foreach (var condition in Conditions)
        {
            condition.PropertyChanged += OnNestedPropertyChanged;
        }
        foreach (var modifier in Modifiers)
        {
            modifier.PropertyChanged += OnNestedPropertyChanged;
        }
        PropertyChanged += OnOwnPropertyChanged;
    }

    /// <summary>Raised when this event or any condition/modifier beneath it is edited.</summary>
    public event EventHandler? Edited;

    public static StemEventViewModel CreateNew()
    {
        var stemEvent = new StemEvent
        {
            EventId = "new_event",
            Condition = new MphCondition { Operator = ComparisonOperator.GreaterThan, Value = 5.0 }
        };
        stemEvent.Modifiers.Add(new GainMultiplierModifier { Multiplier = 1.0 });
        return new StemEventViewModel(stemEvent);
    }

    [ObservableProperty]
    private string _eventId;

    [ObservableProperty]
    private string _displayName;

    /// <summary>True writes an <c>all</c> condition, false writes an <c>any</c> condition.</summary>
    [ObservableProperty]
    private bool _requiresAllConditions;

    public ObservableCollection<MphConditionViewModel> Conditions { get; }

    public ObservableCollection<StemModifierViewModel> Modifiers { get; }

    public string Summary =>
        $"{(string.IsNullOrWhiteSpace(DisplayName) ? EventId : DisplayName)} · " +
        $"{Conditions.Count} condition(s) · {Modifiers.Count} modifier(s)";

    public StemEvent ToManifest()
    {
        _source.EventId = string.IsNullOrWhiteSpace(EventId) ? null : EventId.Trim();
        _source.DisplayName = string.IsNullOrWhiteSpace(DisplayName) ? null : DisplayName.Trim();

        var leaves = Conditions.Select(condition => condition.ToManifest()).ToList();
        _source.Condition = leaves.Count switch
        {
            0 => new MphCondition { Operator = ComparisonOperator.GreaterThanOrEqual, Value = 0.0 },
            1 => leaves[0],
            _ => BuildComposite(leaves)
        };

        _source.Modifiers.Clear();
        foreach (var modifier in Modifiers)
        {
            _source.Modifiers.Add(modifier.ToManifest());
        }

        return _source;
    }

    private EventCondition BuildComposite(List<MphCondition> leaves)
    {
        if (RequiresAllConditions)
        {
            var all = new AllCondition();
            all.Conditions.AddRange(leaves);
            return all;
        }

        var any = new AnyCondition();
        any.Conditions.AddRange(leaves);
        return any;
    }

    /// <summary>
    /// Flattens a condition tree into the flat list the inspector edits.
    ///
    /// The editor offers "all of" / "any of" over a list of mph comparisons, which covers every
    /// condition the runtime evaluates today; a deeper tree from a future tool is flattened for
    /// display and rewritten on save.
    /// </summary>
    private static IEnumerable<MphConditionViewModel> Flatten(EventCondition condition) => condition switch
    {
        MphCondition mph => new[] { new MphConditionViewModel(mph) },
        AllCondition all => all.Conditions.SelectMany(Flatten),
        AnyCondition any => any.Conditions.SelectMany(Flatten),
        _ => Array.Empty<MphConditionViewModel>()
    };

    private void OnOwnPropertyChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (args.PropertyName == nameof(Summary))
        {
            return;
        }

        if (args.PropertyName is nameof(EventId) or nameof(DisplayName))
        {
            OnPropertyChanged(nameof(Summary));
        }

        Edited?.Invoke(this, EventArgs.Empty);
    }

    private void OnConditionsCollectionChanged(object? sender, NotifyCollectionChangedEventArgs args)
    {
        UpdateNestedSubscriptions<MphConditionViewModel>(args);
        OnPropertyChanged(nameof(Summary));
        Edited?.Invoke(this, EventArgs.Empty);
    }

    private void OnModifiersCollectionChanged(object? sender, NotifyCollectionChangedEventArgs args)
    {
        UpdateNestedSubscriptions<StemModifierViewModel>(args);
        OnPropertyChanged(nameof(Summary));
        Edited?.Invoke(this, EventArgs.Empty);
    }

    private void UpdateNestedSubscriptions<T>(NotifyCollectionChangedEventArgs args) where T : INotifyPropertyChanged
    {
        if (args.OldItems is not null)
        {
            foreach (T item in args.OldItems)
            {
                item.PropertyChanged -= OnNestedPropertyChanged;
            }
        }

        if (args.NewItems is not null)
        {
            foreach (T item in args.NewItems)
            {
                item.PropertyChanged += OnNestedPropertyChanged;
            }
        }
    }

    private void OnNestedPropertyChanged(object? sender, PropertyChangedEventArgs args) =>
        Edited?.Invoke(this, EventArgs.Empty);
}

public partial class MphConditionViewModel : ObservableObject
{
    private readonly MphCondition _source;

    public MphConditionViewModel(MphCondition source)
    {
        _source = source;
        _operatorToken = SongManifestSerializer.OperatorToken(source.Operator);
        _value = source.Value;
    }

    public static MphConditionViewModel CreateNew() =>
        new(new MphCondition { Operator = ComparisonOperator.GreaterThanOrEqual, Value = 25.0 });

    public static IReadOnlyList<string> OperatorTokens { get; } = new[] { ">", ">=", "<", "<=", "==", "!=" };

    [ObservableProperty]
    private string _operatorToken;

    [ObservableProperty]
    private double _value;

    public MphCondition ToManifest()
    {
        _source.Operator = SongManifestSerializer.ParseOperator(OperatorToken);
        _source.Value = Value;
        return _source;
    }
}

public partial class StemModifierViewModel : ObservableObject
{
    private readonly StemModifier _source;
    private bool _updatingMultiplierFromText;

    public StemModifierViewModel(StemModifier source)
    {
        _source = source;
        _isReverb = source is ReverbModifier;
        _fadeMs = source.FadeMs;

        if (source is GainMultiplierModifier gain)
        {
            _multiplier = gain.Multiplier;
            _multiplierText = gain.Multiplier.ToString("G", CultureInfo.InvariantCulture);
        }

        if (source is ReverbModifier reverb)
        {
            _wetMix = reverb.WetMix;
            _feedback = reverb.Feedback;
            _damping = reverb.Damping;
            _delayMs = reverb.DelayMs;
        }
    }

    public static StemModifierViewModel CreateGain() =>
        new(new GainMultiplierModifier { Multiplier = 1.0 });

    public static StemModifierViewModel CreateReverb() =>
        new(new ReverbModifier());

    [ObservableProperty]
    private bool _isReverb;

    [ObservableProperty]
    private long _fadeMs = 1_500;

    [ObservableProperty]
    private double _multiplier = 1.0;

    [ObservableProperty]
    private string _multiplierText = "1";

    [ObservableProperty]
    private double _wetMix = 0.35;

    [ObservableProperty]
    private double _feedback = 0.55;

    [ObservableProperty]
    private double _damping = 0.35;

    [ObservableProperty]
    private double _delayMs = 140.0;

    public string Summary => IsReverb
        ? $"Reverb · wet {WetMix:F2} · feedback {Feedback:F2}"
        : $"Gain × {Multiplier:F2}";

    partial void OnMultiplierTextChanged(string value)
    {
        if ((!double.TryParse(value, NumberStyles.Float, CultureInfo.CurrentCulture, out var parsed) &&
             !double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out parsed)) ||
            !double.IsFinite(parsed) || parsed < 0.0)
        {
            return;
        }

        _updatingMultiplierFromText = true;
        Multiplier = parsed;
        _updatingMultiplierFromText = false;
    }

    partial void OnMultiplierChanged(double value)
    {
        OnPropertyChanged(nameof(Summary));
        if (!_updatingMultiplierFromText)
        {
            MultiplierText = value.ToString("G", CultureInfo.InvariantCulture);
        }
    }

    public StemModifier ToManifest()
    {
        if (IsReverb)
        {
            var reverb = _source as ReverbModifier ?? new ReverbModifier();
            reverb.WetMix = Math.Clamp(WetMix, 0.0, 1.0);
            reverb.Feedback = Math.Clamp(Feedback, 0.0, 0.98);
            reverb.Damping = Math.Clamp(Damping, 0.0, 0.98);
            reverb.DelayMs = Math.Clamp(DelayMs, 20.0, 750.0);
            reverb.FadeMs = FadeMs;
            return reverb;
        }

        var gain = _source as GainMultiplierModifier ?? new GainMultiplierModifier();
        gain.Multiplier = Math.Max(Multiplier, 0.0);
        gain.FadeMs = FadeMs;
        return gain;
    }
}
