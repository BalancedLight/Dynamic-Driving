using System.Text.Json.Nodes;

namespace DynamicDriving.Core.Manifests;

/// <summary>
/// Base for every manifest node, carrying the properties this build does not understand.
/// </summary>
/// <remarks>
/// A manifest written by a newer authoring tool must survive a load/save cycle here untouched, so
/// each node keeps the JSON properties it did not map onto a typed field and the serializer writes
/// them back out verbatim.
/// </remarks>
public abstract class ManifestNode
{
    /// <summary>Properties read from JSON that this build has no typed field for.</summary>
    public JsonObject UnknownProperties { get; } = new();

    internal void CaptureUnknown(JsonObject source, IReadOnlySet<string> knownKeys)
    {
        UnknownProperties.Clear();
        foreach (var property in source)
        {
            if (knownKeys.Contains(property.Key))
            {
                continue;
            }

            UnknownProperties[property.Key] = property.Value?.DeepClone();
        }
    }

    internal void WriteUnknown(JsonObject target)
    {
        foreach (var property in UnknownProperties)
        {
            // A typed field always wins: it is the value the user just edited.
            if (!target.ContainsKey(property.Key))
            {
                target[property.Key] = property.Value?.DeepClone();
            }
        }
    }
}

public sealed class SongManifest : ManifestNode
{
    public string SongId { get; set; } = string.Empty;

    public string DisplayName { get; set; } = string.Empty;

    /// <summary>Optional. Null means "not declared" and is published as nothing, never as a placeholder.</summary>
    public string? Artist { get; set; }

    /// <summary>Optional. Null means "not declared".</summary>
    public string? Album { get; set; }

    public string TransportStemId { get; set; } = string.Empty;

    public LoopRegion LoopRegion { get; set; } = new();

    public MuffleSettings? Muffle { get; set; }

    public List<StemManifest> Stems { get; } = new();

    public StemManifest? TransportStem =>
        Stems.FirstOrDefault(stem => stem.StemId == TransportStemId) ?? Stems.FirstOrDefault();

    public SongManifest Clone() => SongManifestSerializer.Deserialize(SongManifestSerializer.Serialize(this));
}

public sealed class LoopRegion : ManifestNode
{
    public long StartMs { get; set; }

    public long EndMs { get; set; } = 10_000;

    public bool PlayTailOverLoop { get; set; }

    public bool LoopStartsSong { get; set; } = true;

    public long DurationMs => Math.Max(0, EndMs - StartMs);
}

public sealed class MuffleSettings : ManifestNode
{
    public double ReleaseMph { get; set; } = 1.0;

    public double WetMix { get; set; } = 0.85;

    public double CutoffHz { get; set; } = 300.0;

    public long FadeMs { get; set; } = 1_200;
}

public sealed class StemManifest : ManifestNode
{
    public string StemId { get; set; } = string.Empty;

    public string DisplayName { get; set; } = string.Empty;

    /// <summary>Path to the stem's WAV, relative to song.json. Forward slashes.</summary>
    public string AssetPath { get; set; } = string.Empty;

    public bool PlayTailOverLoop { get; set; } = true;

    public double Gain { get; set; } = 1.0;

    public long FadeInMs { get; set; } = 1_500;

    public long FadeOutMs { get; set; } = 1_500;

    public StemRule Rule { get; set; } = new BaseStemRule();

    public List<StemEvent> Events { get; } = new();

    public string Label => !string.IsNullOrWhiteSpace(DisplayName)
        ? DisplayName
        : !string.IsNullOrWhiteSpace(StemId) ? StemId : AssetPath;
}

public abstract class StemRule : ManifestNode
{
    public double? MinMph { get; set; }

    public double? MaxMphExclusive { get; set; }
}

public sealed class BaseStemRule : StemRule
{
}

public sealed class OverlayStemRule : StemRule
{
    public string GroupId { get; set; } = string.Empty;

    public string? GroupName { get; set; }

    public long DurationMs { get; set; } = 20_000;

    public long CooldownMinMs { get; set; } = 20_000;

    public long CooldownMaxMs { get; set; } = 40_000;

    public int Weight { get; set; } = 1;
}

public sealed class StemEvent : ManifestNode
{
    public string? EventId { get; set; }

    public string? DisplayName { get; set; }

    public EventCondition Condition { get; set; } = new MphCondition();

    public List<StemModifier> Modifiers { get; } = new();
}

public abstract class EventCondition : ManifestNode
{
}

public sealed class AllCondition : EventCondition
{
    public List<EventCondition> Conditions { get; } = new();
}

public sealed class AnyCondition : EventCondition
{
    public List<EventCondition> Conditions { get; } = new();
}

public enum ComparisonOperator
{
    GreaterThan,
    GreaterThanOrEqual,
    LessThan,
    LessThanOrEqual,
    Equal,
    NotEqual
}

public sealed class MphCondition : EventCondition
{
    public ComparisonOperator Operator { get; set; } = ComparisonOperator.GreaterThan;

    public double Value { get; set; } = 5.0;
}

public abstract class StemModifier : ManifestNode
{
    public long FadeMs { get; set; } = 1_500;
}

public sealed class GainMultiplierModifier : StemModifier
{
    public double Multiplier { get; set; } = 1.0;
}

public sealed class ReverbModifier : StemModifier
{
    public double WetMix { get; set; } = 0.35;

    public double Feedback { get; set; } = 0.55;

    public double Damping { get; set; } = 0.35;

    public double DelayMs { get; set; } = 140.0;
}
