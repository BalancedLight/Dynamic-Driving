using DynamicDriving.Core.Manifests;

namespace DynamicDriving.Core.Playback;

public readonly record struct OverlayGroupState(
    string? ActiveStemId = null,
    long ActiveUntilMs = 0,
    long CooldownUntilMs = 0);

public abstract record OverlayGroupAction
{
    public sealed record None : OverlayGroupAction;

    public sealed record Start(StemManifest Stem, long ActiveUntilMs) : OverlayGroupAction;

    public sealed record Stop(string StemId, long CooldownUntilMs) : OverlayGroupAction;
}

public readonly record struct OverlayGroupDecision(OverlayGroupState State, OverlayGroupAction Action);

/// <summary>
/// One overlay per group at a time, with a randomised cooldown between plays.
///
/// This is the same scheduler the Android engine runs, so the editor's preview shows the real
/// pacing rather than firing every eligible overlay at once.
/// </summary>
public static class OverlayGroupScheduler
{
    public static OverlayGroupDecision Tick(
        OverlayGroupState currentState,
        long nowMs,
        IReadOnlyList<StemManifest> eligibleCandidates,
        StemManifest? activeStem,
        bool forceStopActive,
        Random random)
    {
        if (currentState.ActiveStemId is not null && activeStem is not null)
        {
            var overlayRule = (OverlayStemRule)activeStem.Rule;
            if (forceStopActive || nowMs >= currentState.ActiveUntilMs)
            {
                var cooldownUntilMs = nowMs + PlaybackRules.NextCooldownMs(overlayRule, random);
                return new OverlayGroupDecision(
                    currentState with
                    {
                        ActiveStemId = null,
                        ActiveUntilMs = 0,
                        CooldownUntilMs = cooldownUntilMs
                    },
                    new OverlayGroupAction.Stop(activeStem.StemId, cooldownUntilMs));
            }

            return new OverlayGroupDecision(currentState, new OverlayGroupAction.None());
        }

        if (nowMs < currentState.CooldownUntilMs)
        {
            return new OverlayGroupDecision(currentState, new OverlayGroupAction.None());
        }

        var selectedStem = PlaybackRules.ChooseWeightedOverlay(eligibleCandidates, random);
        if (selectedStem is null)
        {
            return new OverlayGroupDecision(currentState, new OverlayGroupAction.None());
        }

        var selectedRule = (OverlayStemRule)selectedStem.Rule;
        var activeUntilMs = nowMs + selectedRule.DurationMs;
        return new OverlayGroupDecision(
            new OverlayGroupState(selectedStem.StemId, activeUntilMs, currentState.CooldownUntilMs),
            new OverlayGroupAction.Start(selectedStem, activeUntilMs));
    }
}
