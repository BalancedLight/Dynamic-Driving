using DynamicDriving.Core.Manifests;

namespace DynamicDriving.Core.Playback;

/// <summary>
/// The speed rules the Android engine applies, ported so a preview in the editor sounds like the
/// car does.
/// </summary>
public static class PlaybackRules
{
    public const double HysteresisMph = 2.0;

    /// <summary>
    /// Whether a base stem should be audible at [mph].
    ///
    /// The bounds widen by [hysteresisMph] while the stem is already active so a speed hovering on a
    /// threshold does not chatter the layer in and out.
    /// </summary>
    public static bool ShouldActivateBaseStem(
        StemRule rule,
        double mph,
        bool currentlyActive,
        double hysteresisMph = HysteresisMph)
    {
        var min = rule.MinMph ?? double.NegativeInfinity;
        var max = rule.MaxMphExclusive ?? double.PositiveInfinity;
        var lowerBound = currentlyActive ? min - hysteresisMph : min;
        var upperBound = currentlyActive ? max + hysteresisMph : max;
        return mph >= lowerBound && mph < upperBound;
    }

    public static bool IsOverlayEligible(
        OverlayStemRule rule,
        double mph,
        bool currentlyActive,
        double hysteresisMph = HysteresisMph)
    {
        var min = rule.MinMph ?? 0.0;
        var max = rule.MaxMphExclusive ?? double.PositiveInfinity;
        var lowerBound = currentlyActive ? min - hysteresisMph : min;
        var upperBound = currentlyActive ? max + hysteresisMph : max;
        return mph >= lowerBound && mph < upperBound;
    }

    public static bool EvaluateCondition(EventCondition condition, double mph) => condition switch
    {
        AllCondition all => all.Conditions.All(child => EvaluateCondition(child, mph)),
        AnyCondition any => any.Conditions.Any(child => EvaluateCondition(child, mph)),
        MphCondition mphCondition => Compare(mph, mphCondition.Operator, mphCondition.Value),
        _ => false
    };

    public static bool Compare(double actual, ComparisonOperator comparisonOperator, double expected) =>
        comparisonOperator switch
        {
            ComparisonOperator.GreaterThan => actual > expected,
            ComparisonOperator.GreaterThanOrEqual => actual >= expected,
            ComparisonOperator.LessThan => actual < expected,
            ComparisonOperator.LessThanOrEqual => actual <= expected,
            ComparisonOperator.Equal => Math.Abs(actual - expected) < double.Epsilon,
            ComparisonOperator.NotEqual => Math.Abs(actual - expected) >= double.Epsilon,
            _ => false
        };

    /// <summary>Picks one overlay from [candidates], weighted by each rule's <c>weight</c>.</summary>
    public static StemManifest? ChooseWeightedOverlay(IReadOnlyList<StemManifest> candidates, Random random)
    {
        if (candidates.Count == 0)
        {
            return null;
        }

        var totalWeight = candidates.Sum(stem => Math.Max(1, ((OverlayStemRule)stem.Rule).Weight));
        if (totalWeight <= 0)
        {
            return candidates[0];
        }

        var draw = random.Next(totalWeight);
        foreach (var stem in candidates)
        {
            draw -= Math.Max(1, ((OverlayStemRule)stem.Rule).Weight);
            if (draw < 0)
            {
                return stem;
            }
        }

        return candidates[^1];
    }

    public static long NextCooldownMs(OverlayStemRule rule, Random random)
    {
        if (rule.CooldownMaxMs <= rule.CooldownMinMs)
        {
            return rule.CooldownMinMs;
        }

        return random.NextInt64(rule.CooldownMinMs, rule.CooldownMaxMs + 1);
    }

    /// <summary>How much a base stem is pulled down and muffled while the car is barely moving.</summary>
    public static IdleBaseEffect CalculateIdleBaseEffect(double smoothedSpeedMph)
    {
        const double releaseMph = 6.0;
        const double minGainMultiplier = 0.5;
        const double maxMuffleAmount = 0.6;

        var blend = Math.Clamp((releaseMph - Math.Max(0.0, smoothedSpeedMph)) / releaseMph, 0.0, 1.0);
        if (blend <= 0.0)
        {
            return IdleBaseEffect.None;
        }

        var gainMultiplier = 1.0 - ((1.0 - minGainMultiplier) * blend);
        var muffleAmount = maxMuffleAmount * blend;
        var cutoffHz = ClearCutoffHz - ((ClearCutoffHz - 300.0) * blend);
        return new IdleBaseEffect(gainMultiplier, muffleAmount, cutoffHz);
    }

    /// <summary>Song-level muffle that fades out as the car gets going.</summary>
    public static SongMuffleEffect CalculateSongMuffleEffect(MuffleSettings? muffle, double smoothedSpeedMph)
    {
        if (muffle is null)
        {
            return SongMuffleEffect.None;
        }

        var releaseMph = Math.Max(0.001, muffle.ReleaseMph);
        var blend = Math.Clamp((releaseMph - Math.Max(0.0, smoothedSpeedMph)) / releaseMph, 0.0, 1.0);
        if (blend <= 0.0)
        {
            return SongMuffleEffect.None;
        }

        var cutoffHz = ClearCutoffHz - ((ClearCutoffHz - muffle.CutoffHz) * blend);
        return new SongMuffleEffect(
            Math.Clamp(muffle.WetMix * blend, 0.0, 1.0),
            Math.Clamp(cutoffHz, Math.Max(80.0, muffle.CutoffHz), ClearCutoffHz));
    }

    public const double ClearCutoffHz = 18_000.0;
}

public readonly record struct IdleBaseEffect(double GainMultiplier, double MuffleAmount, double MuffleCutoffHz)
{
    public static IdleBaseEffect None { get; } = new(1.0, 0.0, PlaybackRules.ClearCutoffHz);

    public bool IsActive => GainMultiplier < 0.999 || MuffleAmount > 0.001;
}

public readonly record struct SongMuffleEffect(double MuffleAmount, double MuffleCutoffHz)
{
    public static SongMuffleEffect None { get; } = new(0.0, PlaybackRules.ClearCutoffHz);

    public bool IsActive => MuffleAmount > 0.001;
}
