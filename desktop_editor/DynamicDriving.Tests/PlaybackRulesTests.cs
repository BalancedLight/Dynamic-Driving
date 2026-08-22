using DynamicDriving.Core.Manifests;
using DynamicDriving.Core.Playback;
using Xunit;

namespace DynamicDriving.Tests;

public sealed class PlaybackRulesTests
{
    [Fact]
    public void A_base_stem_with_no_bounds_is_always_active()
    {
        var rule = new BaseStemRule();

        Assert.True(PlaybackRules.ShouldActivateBaseStem(rule, 0, currentlyActive: false));
        Assert.True(PlaybackRules.ShouldActivateBaseStem(rule, 120, currentlyActive: false));
    }

    [Fact]
    public void Hysteresis_widens_the_band_only_while_the_stem_is_already_active()
    {
        var rule = new BaseStemRule { MinMph = 20.0, MaxMphExclusive = 60.0 };

        Assert.False(PlaybackRules.ShouldActivateBaseStem(rule, 19.0, currentlyActive: false));
        Assert.True(PlaybackRules.ShouldActivateBaseStem(rule, 19.0, currentlyActive: true));
        Assert.False(PlaybackRules.ShouldActivateBaseStem(rule, 61.0, currentlyActive: false));
        Assert.True(PlaybackRules.ShouldActivateBaseStem(rule, 61.0, currentlyActive: true));
        Assert.False(PlaybackRules.ShouldActivateBaseStem(rule, 17.0, currentlyActive: true));
    }

    [Fact]
    public void Overlay_eligibility_uses_the_same_hysteresis()
    {
        var rule = new OverlayStemRule { MinMph = 30.0, MaxMphExclusive = 70.0, GroupId = "lead" };

        Assert.False(PlaybackRules.IsOverlayEligible(rule, 29.0, currentlyActive: false));
        Assert.True(PlaybackRules.IsOverlayEligible(rule, 29.0, currentlyActive: true));
        Assert.True(PlaybackRules.IsOverlayEligible(rule, 30.0, currentlyActive: false));
    }

    [Theory]
    [InlineData(">", 10.0, 5.0, true)]
    [InlineData(">=", 5.0, 5.0, true)]
    [InlineData("<", 4.0, 5.0, true)]
    [InlineData("<=", 5.0, 5.0, true)]
    [InlineData("==", 5.0, 5.0, true)]
    [InlineData("!=", 6.0, 5.0, true)]
    [InlineData(">", 4.0, 5.0, false)]
    [InlineData("<", 6.0, 5.0, false)]
    public void Comparison_operators_behave_as_documented(
        string token,
        double actual,
        double expected,
        bool result)
    {
        var comparisonOperator = SongManifestSerializer.ParseOperator(token);

        Assert.Equal(result, PlaybackRules.Compare(actual, comparisonOperator, expected));
    }

    [Fact]
    public void All_and_any_conditions_compose()
    {
        var allCondition = new AllCondition();
        allCondition.Conditions.Add(new MphCondition { Operator = ComparisonOperator.GreaterThan, Value = 5 });
        allCondition.Conditions.Add(new MphCondition { Operator = ComparisonOperator.LessThan, Value = 35 });

        Assert.True(PlaybackRules.EvaluateCondition(allCondition, 20));
        Assert.False(PlaybackRules.EvaluateCondition(allCondition, 40));

        var anyCondition = new AnyCondition();
        anyCondition.Conditions.Add(new MphCondition { Operator = ComparisonOperator.LessThanOrEqual, Value = 2 });
        anyCondition.Conditions.Add(new MphCondition { Operator = ComparisonOperator.GreaterThanOrEqual, Value = 70 });

        Assert.True(PlaybackRules.EvaluateCondition(anyCondition, 1));
        Assert.True(PlaybackRules.EvaluateCondition(anyCondition, 80));
        Assert.False(PlaybackRules.EvaluateCondition(anyCondition, 30));
    }

    [Fact]
    public void Weighted_overlay_selection_favours_the_heavier_candidate()
    {
        var light = OverlayStem("light", weight: 1);
        var heavy = OverlayStem("heavy", weight: 9);
        var candidates = new[] { light, heavy };

        var heavyWins = 0;
        for (var seed = 0; seed < 200; seed++)
        {
            var chosen = PlaybackRules.ChooseWeightedOverlay(candidates, new Random(seed));
            if (chosen?.StemId == "heavy")
            {
                heavyWins++;
            }
        }

        Assert.True(heavyWins > 140, $"Expected the 9:1 weighting to dominate, got {heavyWins}/200.");
    }

    [Fact]
    public void An_empty_candidate_list_selects_nothing()
    {
        Assert.Null(PlaybackRules.ChooseWeightedOverlay(Array.Empty<StemManifest>(), new Random(1)));
    }

    [Fact]
    public void Cooldown_falls_back_to_the_minimum_when_the_range_is_inverted()
    {
        var rule = new OverlayStemRule { GroupId = "lead", CooldownMinMs = 5_000, CooldownMaxMs = 1_000 };

        Assert.Equal(5_000, PlaybackRules.NextCooldownMs(rule, new Random(1)));
    }

    [Fact]
    public void The_overlay_scheduler_starts_stops_and_then_waits_out_its_cooldown()
    {
        var stem = OverlayStem("lead", weight: 1);
        var rule = (OverlayStemRule)stem.Rule;
        rule.DurationMs = 1_000;
        rule.CooldownMinMs = 2_000;
        rule.CooldownMaxMs = 2_000;

        var random = new Random(3);
        var state = new OverlayGroupState();

        var start = OverlayGroupScheduler.Tick(state, 0, new[] { stem }, null, false, random);
        var startAction = Assert.IsType<OverlayGroupAction.Start>(start.Action);
        Assert.Equal("lead", startAction.Stem.StemId);
        state = start.State;

        var holding = OverlayGroupScheduler.Tick(state, 500, new[] { stem }, stem, false, random);
        Assert.IsType<OverlayGroupAction.None>(holding.Action);
        state = holding.State;

        var stop = OverlayGroupScheduler.Tick(state, 1_000, new[] { stem }, stem, false, random);
        Assert.IsType<OverlayGroupAction.Stop>(stop.Action);
        state = stop.State;

        var cooling = OverlayGroupScheduler.Tick(state, 1_500, new[] { stem }, null, false, random);
        Assert.IsType<OverlayGroupAction.None>(cooling.Action);

        var restarted = OverlayGroupScheduler.Tick(state, 4_000, new[] { stem }, null, false, random);
        Assert.IsType<OverlayGroupAction.Start>(restarted.Action);
    }

    [Fact]
    public void An_overlay_that_becomes_ineligible_is_stopped_immediately()
    {
        var stem = OverlayStem("lead", weight: 1);
        ((OverlayStemRule)stem.Rule).DurationMs = 10_000;
        var random = new Random(5);

        var start = OverlayGroupScheduler.Tick(new OverlayGroupState(), 0, new[] { stem }, null, false, random);
        var stopped = OverlayGroupScheduler.Tick(start.State, 100, Array.Empty<StemManifest>(), stem, true, random);

        Assert.IsType<OverlayGroupAction.Stop>(stopped.Action);
    }

    [Fact]
    public void Idle_effects_release_as_the_car_gets_going()
    {
        var stationary = PlaybackRules.CalculateIdleBaseEffect(0);
        var moving = PlaybackRules.CalculateIdleBaseEffect(30);

        Assert.True(stationary.IsActive);
        Assert.True(stationary.GainMultiplier < 1.0);
        Assert.True(stationary.MuffleAmount > 0.0);
        Assert.False(moving.IsActive);
    }

    [Fact]
    public void Song_muffle_releases_at_its_configured_speed()
    {
        var muffle = new MuffleSettings { ReleaseMph = 10.0, WetMix = 0.8, CutoffHz = 300.0 };

        Assert.True(PlaybackRules.CalculateSongMuffleEffect(muffle, 0).IsActive);
        Assert.False(PlaybackRules.CalculateSongMuffleEffect(muffle, 12).IsActive);
        Assert.False(PlaybackRules.CalculateSongMuffleEffect(null, 0).IsActive);
    }

    private static StemManifest OverlayStem(string stemId, int weight) => new()
    {
        StemId = stemId,
        DisplayName = stemId,
        AssetPath = $"{stemId}.wav",
        Rule = new OverlayStemRule
        {
            MinMph = 0.0,
            GroupId = "lead",
            Weight = weight,
            DurationMs = 5_000,
            CooldownMinMs = 1_000,
            CooldownMaxMs = 1_000
        }
    };
}
