using System.Text.Json;
using System.Text.Json.Nodes;

namespace DynamicDriving.Core.Manifests;

/// <summary>Raised when a manifest cannot be understood at all.</summary>
public sealed class SongManifestFormatException : Exception
{
    public SongManifestFormatException(string message) : base(message)
    {
    }
}

/// <summary>
/// Reads and writes <c>song.json</c>.
///
/// The shape this implements is the one described by <c>contracts/song.schema.json</c>; the
/// fixtures in <c>contracts/fixtures</c> pin it against the Kotlin parser.
/// </summary>
public static class SongManifestSerializer
{
    private static readonly JsonSerializerOptions WriteOptions = new()
    {
        WriteIndented = true
    };

    private static readonly IReadOnlySet<string> SongKeys = new HashSet<string>
    {
        "songId", "displayName", "artist", "album", "transportStemId", "loopRegion", "muffle", "stems"
    };

    private static readonly IReadOnlySet<string> LoopKeys = new HashSet<string>
    {
        "startMs", "endMs", "playTailOverLoop", "loopStartsSong"
    };

    private static readonly IReadOnlySet<string> MuffleKeys = new HashSet<string>
    {
        "releaseMph", "wetMix", "cutoffHz", "fadeMs"
    };

    private static readonly IReadOnlySet<string> StemKeys = new HashSet<string>
    {
        "stemId", "displayName", "assetPath", "playTailOverLoop", "gain", "fadeInMs", "fadeOutMs", "rule", "events"
    };

    private static readonly IReadOnlySet<string> BaseRuleKeys = new HashSet<string>
    {
        "type", "minMph", "maxMphExclusive"
    };

    private static readonly IReadOnlySet<string> OverlayRuleKeys = new HashSet<string>
    {
        "type", "minMph", "maxMphExclusive", "groupId", "groupName",
        "durationMs", "cooldownMinMs", "cooldownMaxMs", "weight"
    };

    private static readonly IReadOnlySet<string> EventKeys = new HashSet<string>
    {
        "eventId", "displayName", "condition", "modifiers"
    };

    private static readonly IReadOnlySet<string> AllConditionKeys = new HashSet<string> { "all" };

    private static readonly IReadOnlySet<string> AnyConditionKeys = new HashSet<string> { "any" };

    private static readonly IReadOnlySet<string> MphConditionKeys = new HashSet<string>
    {
        "metric", "operator", "value"
    };

    private static readonly IReadOnlySet<string> GainModifierKeys = new HashSet<string>
    {
        "type", "multiplier", "value", "fadeMs"
    };

    private static readonly IReadOnlySet<string> ReverbModifierKeys = new HashSet<string>
    {
        "type", "wetMix", "feedback", "damping", "delayMs", "fadeMs"
    };

    public static SongManifest Deserialize(string json)
    {
        JsonNode? root;
        try
        {
            root = JsonNode.Parse(json);
        }
        catch (JsonException error)
        {
            throw new SongManifestFormatException($"song.json is not valid JSON: {error.Message}");
        }

        if (root is not JsonObject songJson)
        {
            throw new SongManifestFormatException("song.json must contain a JSON object.");
        }

        var manifest = new SongManifest
        {
            SongId = RequireString(songJson, "songId"),
            DisplayName = RequireString(songJson, "displayName"),
            Artist = OptionalString(songJson, "artist"),
            Album = OptionalString(songJson, "album"),
            TransportStemId = RequireString(songJson, "transportStemId"),
            LoopRegion = ParseLoopRegion(RequireObject(songJson, "loopRegion"))
        };
        manifest.CaptureUnknown(songJson, SongKeys);

        if (songJson["muffle"] is JsonObject muffleJson)
        {
            manifest.Muffle = ParseMuffle(muffleJson);
        }

        if (songJson["stems"] is not JsonArray stemsJson || stemsJson.Count == 0)
        {
            throw new SongManifestFormatException("song.json must declare at least one stem.");
        }

        foreach (var stemNode in stemsJson)
        {
            if (stemNode is not JsonObject stemJson)
            {
                throw new SongManifestFormatException("Every entry in \"stems\" must be an object.");
            }

            manifest.Stems.Add(ParseStem(stemJson));
        }

        return manifest;
    }

    public static string Serialize(SongManifest manifest)
    {
        var songJson = new JsonObject
        {
            ["songId"] = manifest.SongId,
            ["displayName"] = manifest.DisplayName
        };

        if (!string.IsNullOrWhiteSpace(manifest.Artist))
        {
            songJson["artist"] = manifest.Artist!.Trim();
        }

        if (!string.IsNullOrWhiteSpace(manifest.Album))
        {
            songJson["album"] = manifest.Album!.Trim();
        }

        songJson["transportStemId"] = manifest.TransportStemId;
        songJson["loopRegion"] = WriteLoopRegion(manifest.LoopRegion);

        if (manifest.Muffle is { } muffle)
        {
            songJson["muffle"] = WriteMuffle(muffle);
        }

        var stemsJson = new JsonArray();
        foreach (var stem in manifest.Stems)
        {
            stemsJson.Add(WriteStem(stem));
        }

        songJson["stems"] = stemsJson;
        manifest.WriteUnknown(songJson);

        return songJson.ToJsonString(WriteOptions) + Environment.NewLine;
    }

    // ------------------------------------------------------------------ reading

    private static LoopRegion ParseLoopRegion(JsonObject loopJson)
    {
        var loop = new LoopRegion
        {
            StartMs = RequireLong(loopJson, "startMs"),
            EndMs = RequireLong(loopJson, "endMs"),
            PlayTailOverLoop = OptionalBool(loopJson, "playTailOverLoop") ?? false,
            LoopStartsSong = OptionalBool(loopJson, "loopStartsSong") ?? true
        };
        loop.CaptureUnknown(loopJson, LoopKeys);
        return loop;
    }

    private static MuffleSettings ParseMuffle(JsonObject muffleJson)
    {
        var muffle = new MuffleSettings
        {
            ReleaseMph = OptionalDouble(muffleJson, "releaseMph") ?? 1.0,
            WetMix = OptionalDouble(muffleJson, "wetMix") ?? 0.85,
            CutoffHz = OptionalDouble(muffleJson, "cutoffHz") ?? 300.0,
            FadeMs = OptionalLong(muffleJson, "fadeMs") ?? 1_200
        };
        muffle.CaptureUnknown(muffleJson, MuffleKeys);
        return muffle;
    }

    private static StemManifest ParseStem(JsonObject stemJson)
    {
        var stem = new StemManifest
        {
            StemId = RequireString(stemJson, "stemId"),
            DisplayName = RequireString(stemJson, "displayName"),
            AssetPath = RequireString(stemJson, "assetPath"),
            PlayTailOverLoop = OptionalBool(stemJson, "playTailOverLoop") ?? true,
            Gain = OptionalDouble(stemJson, "gain") ?? 1.0,
            FadeInMs = OptionalLong(stemJson, "fadeInMs") ?? 1_500,
            FadeOutMs = OptionalLong(stemJson, "fadeOutMs") ?? 1_500,
            Rule = ParseRule(RequireObject(stemJson, "rule"))
        };
        stem.CaptureUnknown(stemJson, StemKeys);

        if (stemJson["events"] is JsonArray eventsJson)
        {
            var index = 0;
            foreach (var eventNode in eventsJson)
            {
                index++;
                if (eventNode is not JsonObject eventJson)
                {
                    throw new SongManifestFormatException("Every entry in \"events\" must be an object.");
                }

                stem.Events.Add(ParseEvent(eventJson, index));
            }
        }

        return stem;
    }

    private static StemRule ParseRule(JsonObject ruleJson)
    {
        var type = RequireString(ruleJson, "type");
        switch (type)
        {
            case "base":
            {
                var rule = new BaseStemRule
                {
                    MinMph = OptionalDouble(ruleJson, "minMph"),
                    MaxMphExclusive = OptionalDouble(ruleJson, "maxMphExclusive")
                };
                rule.CaptureUnknown(ruleJson, BaseRuleKeys);
                return rule;
            }

            case "overlay":
            {
                var rule = new OverlayStemRule
                {
                    MinMph = OptionalDouble(ruleJson, "minMph")
                             ?? throw new SongManifestFormatException("An overlay rule requires \"minMph\"."),
                    MaxMphExclusive = OptionalDouble(ruleJson, "maxMphExclusive"),
                    GroupId = RequireString(ruleJson, "groupId"),
                    GroupName = OptionalString(ruleJson, "groupName"),
                    DurationMs = OptionalLong(ruleJson, "durationMs") ?? 20_000,
                    CooldownMinMs = OptionalLong(ruleJson, "cooldownMinMs") ?? 20_000,
                    CooldownMaxMs = OptionalLong(ruleJson, "cooldownMaxMs") ?? 40_000,
                    Weight = (int)(OptionalLong(ruleJson, "weight") ?? 1)
                };
                rule.CaptureUnknown(ruleJson, OverlayRuleKeys);
                return rule;
            }

            default:
                throw new SongManifestFormatException($"Unsupported rule type \"{type}\".");
        }
    }

    private static StemEvent ParseEvent(JsonObject eventJson, int index)
    {
        var stemEvent = new StemEvent
        {
            EventId = OptionalString(eventJson, "eventId") ?? $"event_{index}",
            DisplayName = OptionalString(eventJson, "displayName"),
            Condition = ParseCondition(RequireObject(eventJson, "condition"))
        };
        stemEvent.CaptureUnknown(eventJson, EventKeys);

        if (eventJson["modifiers"] is not JsonArray modifiersJson || modifiersJson.Count == 0)
        {
            throw new SongManifestFormatException("A stem event must declare at least one modifier.");
        }

        foreach (var modifierNode in modifiersJson)
        {
            if (modifierNode is not JsonObject modifierJson)
            {
                throw new SongManifestFormatException("Every entry in \"modifiers\" must be an object.");
            }

            stemEvent.Modifiers.Add(ParseModifier(modifierJson));
        }

        return stemEvent;
    }

    private static EventCondition ParseCondition(JsonObject conditionJson)
    {
        if (conditionJson["all"] is JsonArray allJson)
        {
            var condition = new AllCondition();
            condition.CaptureUnknown(conditionJson, AllConditionKeys);
            foreach (var child in allJson)
            {
                condition.Conditions.Add(ParseCondition(AsObject(child, "all")));
            }

            return condition;
        }

        if (conditionJson["any"] is JsonArray anyJson)
        {
            var condition = new AnyCondition();
            condition.CaptureUnknown(conditionJson, AnyConditionKeys);
            foreach (var child in anyJson)
            {
                condition.Conditions.Add(ParseCondition(AsObject(child, "any")));
            }

            return condition;
        }

        var metric = OptionalString(conditionJson, "metric") ?? "mph";
        if (!string.Equals(metric, "mph", StringComparison.OrdinalIgnoreCase))
        {
            throw new SongManifestFormatException($"Unsupported event condition metric \"{metric}\".");
        }

        var mphCondition = new MphCondition
        {
            Operator = ParseOperator(RequireString(conditionJson, "operator")),
            Value = OptionalDouble(conditionJson, "value")
                    ?? throw new SongManifestFormatException("An mph condition requires \"value\".")
        };
        mphCondition.CaptureUnknown(conditionJson, MphConditionKeys);
        return mphCondition;
    }

    private static StemModifier ParseModifier(JsonObject modifierJson)
    {
        var type = RequireString(modifierJson, "type");
        switch (type)
        {
            case "gainMultiplier":
            {
                var modifier = new GainMultiplierModifier
                {
                    Multiplier = Math.Max(
                        OptionalDouble(modifierJson, "multiplier") ?? OptionalDouble(modifierJson, "value") ?? 1.0,
                        0.0),
                    FadeMs = OptionalLong(modifierJson, "fadeMs") ?? 1_500
                };
                modifier.CaptureUnknown(modifierJson, GainModifierKeys);
                return modifier;
            }

            case "reverb":
            {
                var modifier = new ReverbModifier
                {
                    WetMix = Math.Clamp(OptionalDouble(modifierJson, "wetMix") ?? 0.35, 0.0, 1.0),
                    Feedback = Math.Clamp(OptionalDouble(modifierJson, "feedback") ?? 0.55, 0.0, 0.98),
                    Damping = Math.Clamp(OptionalDouble(modifierJson, "damping") ?? 0.35, 0.0, 0.98),
                    DelayMs = Math.Clamp(OptionalDouble(modifierJson, "delayMs") ?? 140.0, 20.0, 750.0),
                    FadeMs = OptionalLong(modifierJson, "fadeMs") ?? 1_500
                };
                modifier.CaptureUnknown(modifierJson, ReverbModifierKeys);
                return modifier;
            }

            default:
                throw new SongManifestFormatException($"Unsupported stem modifier type \"{type}\".");
        }
    }

    public static ComparisonOperator ParseOperator(string rawOperator) => rawOperator.ToLowerInvariant() switch
    {
        "gt" or ">" => ComparisonOperator.GreaterThan,
        "gte" or ">=" => ComparisonOperator.GreaterThanOrEqual,
        "lt" or "<" => ComparisonOperator.LessThan,
        "lte" or "<=" => ComparisonOperator.LessThanOrEqual,
        "eq" or "==" => ComparisonOperator.Equal,
        "neq" or "!=" => ComparisonOperator.NotEqual,
        _ => throw new SongManifestFormatException($"Unsupported comparison operator \"{rawOperator}\".")
    };

    public static string OperatorToken(ComparisonOperator comparisonOperator) => comparisonOperator switch
    {
        ComparisonOperator.GreaterThan => ">",
        ComparisonOperator.GreaterThanOrEqual => ">=",
        ComparisonOperator.LessThan => "<",
        ComparisonOperator.LessThanOrEqual => "<=",
        ComparisonOperator.Equal => "==",
        ComparisonOperator.NotEqual => "!=",
        _ => ">"
    };

    // ------------------------------------------------------------------ writing

    private static JsonObject WriteLoopRegion(LoopRegion loop)
    {
        var json = new JsonObject
        {
            ["startMs"] = loop.StartMs,
            ["endMs"] = loop.EndMs
        };

        if (loop.PlayTailOverLoop)
        {
            json["playTailOverLoop"] = true;
        }

        if (!loop.LoopStartsSong)
        {
            json["loopStartsSong"] = false;
        }

        loop.WriteUnknown(json);
        return json;
    }

    private static JsonObject WriteMuffle(MuffleSettings muffle)
    {
        var json = new JsonObject
        {
            ["releaseMph"] = muffle.ReleaseMph,
            ["wetMix"] = muffle.WetMix,
            ["cutoffHz"] = muffle.CutoffHz,
            ["fadeMs"] = muffle.FadeMs
        };
        muffle.WriteUnknown(json);
        return json;
    }

    private static JsonObject WriteStem(StemManifest stem)
    {
        var json = new JsonObject
        {
            ["stemId"] = stem.StemId,
            ["displayName"] = stem.DisplayName,
            ["assetPath"] = stem.AssetPath
        };

        if (!stem.PlayTailOverLoop)
        {
            json["playTailOverLoop"] = false;
        }

        if (Math.Abs(stem.Gain - 1.0) > double.Epsilon)
        {
            json["gain"] = stem.Gain;
        }

        if (stem.FadeInMs != 1_500)
        {
            json["fadeInMs"] = stem.FadeInMs;
        }

        if (stem.FadeOutMs != 1_500)
        {
            json["fadeOutMs"] = stem.FadeOutMs;
        }

        json["rule"] = WriteRule(stem.Rule);

        if (stem.Events.Count > 0)
        {
            var eventsJson = new JsonArray();
            foreach (var stemEvent in stem.Events)
            {
                eventsJson.Add(WriteEvent(stemEvent));
            }

            json["events"] = eventsJson;
        }

        stem.WriteUnknown(json);
        return json;
    }

    private static JsonObject WriteRule(StemRule rule)
    {
        JsonObject json;
        if (rule is OverlayStemRule overlay)
        {
            json = new JsonObject
            {
                ["type"] = "overlay",
                ["minMph"] = overlay.MinMph ?? 0.0
            };

            if (overlay.MaxMphExclusive is { } maxMph)
            {
                json["maxMphExclusive"] = maxMph;
            }

            json["groupId"] = overlay.GroupId;

            if (!string.IsNullOrWhiteSpace(overlay.GroupName))
            {
                json["groupName"] = overlay.GroupName;
            }

            json["durationMs"] = overlay.DurationMs;
            json["cooldownMinMs"] = overlay.CooldownMinMs;
            json["cooldownMaxMs"] = overlay.CooldownMaxMs;

            if (overlay.Weight != 1)
            {
                json["weight"] = overlay.Weight;
            }
        }
        else
        {
            json = new JsonObject { ["type"] = "base" };

            if (rule.MinMph is { } minMph)
            {
                json["minMph"] = minMph;
            }

            if (rule.MaxMphExclusive is { } maxMph)
            {
                json["maxMphExclusive"] = maxMph;
            }
        }

        rule.WriteUnknown(json);
        return json;
    }

    private static JsonObject WriteEvent(StemEvent stemEvent)
    {
        var json = new JsonObject();

        if (!string.IsNullOrWhiteSpace(stemEvent.EventId))
        {
            json["eventId"] = stemEvent.EventId;
        }

        if (!string.IsNullOrWhiteSpace(stemEvent.DisplayName))
        {
            json["displayName"] = stemEvent.DisplayName;
        }

        json["condition"] = WriteCondition(stemEvent.Condition);

        var modifiersJson = new JsonArray();
        foreach (var modifier in stemEvent.Modifiers)
        {
            modifiersJson.Add(WriteModifier(modifier));
        }

        json["modifiers"] = modifiersJson;
        stemEvent.WriteUnknown(json);
        return json;
    }

    private static JsonObject WriteCondition(EventCondition condition)
    {
        JsonObject json;
        switch (condition)
        {
            case AllCondition all:
            {
                var children = new JsonArray();
                foreach (var child in all.Conditions)
                {
                    children.Add(WriteCondition(child));
                }

                json = new JsonObject { ["all"] = children };
                break;
            }

            case AnyCondition any:
            {
                var children = new JsonArray();
                foreach (var child in any.Conditions)
                {
                    children.Add(WriteCondition(child));
                }

                json = new JsonObject { ["any"] = children };
                break;
            }

            case MphCondition mph:
                json = new JsonObject
                {
                    ["metric"] = "mph",
                    ["operator"] = OperatorToken(mph.Operator),
                    ["value"] = mph.Value
                };
                break;

            default:
                throw new SongManifestFormatException("Unsupported event condition.");
        }

        condition.WriteUnknown(json);
        return json;
    }

    private static JsonObject WriteModifier(StemModifier modifier)
    {
        JsonObject json = modifier switch
        {
            GainMultiplierModifier gain => new JsonObject
            {
                ["type"] = "gainMultiplier",
                ["multiplier"] = gain.Multiplier,
                ["fadeMs"] = gain.FadeMs
            },
            ReverbModifier reverb => new JsonObject
            {
                ["type"] = "reverb",
                ["wetMix"] = reverb.WetMix,
                ["feedback"] = reverb.Feedback,
                ["damping"] = reverb.Damping,
                ["delayMs"] = reverb.DelayMs,
                ["fadeMs"] = reverb.FadeMs
            },
            _ => throw new SongManifestFormatException("Unsupported stem modifier.")
        };

        modifier.WriteUnknown(json);
        return json;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject AsObject(JsonNode? node, string context) =>
        node as JsonObject
        ?? throw new SongManifestFormatException($"Every entry in \"{context}\" must be an object.");

    private static JsonObject RequireObject(JsonObject parent, string key) =>
        parent[key] as JsonObject
        ?? throw new SongManifestFormatException($"\"{key}\" is required and must be an object.");

    private static string RequireString(JsonObject parent, string key)
    {
        var value = parent[key]?.GetValue<string>();
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new SongManifestFormatException($"\"{key}\" is required and must be a non-empty string.");
        }

        return value;
    }

    private static string? OptionalString(JsonObject parent, string key)
    {
        if (parent[key] is not JsonValue value)
        {
            return null;
        }

        var text = value.TryGetValue<string>(out var stringValue) ? stringValue : null;
        return string.IsNullOrWhiteSpace(text) ? null : text.Trim();
    }

    private static bool? OptionalBool(JsonObject parent, string key) =>
        parent[key] is JsonValue value && value.TryGetValue<bool>(out var boolValue) ? boolValue : null;

    private static double? OptionalDouble(JsonObject parent, string key) =>
        parent[key] is JsonValue value && value.TryGetValue<double>(out var doubleValue) ? doubleValue : null;

    private static long? OptionalLong(JsonObject parent, string key)
    {
        if (parent[key] is not JsonValue value)
        {
            return null;
        }

        if (value.TryGetValue<long>(out var longValue))
        {
            return longValue;
        }

        return value.TryGetValue<double>(out var doubleValue) ? (long)Math.Round(doubleValue) : null;
    }

    private static long RequireLong(JsonObject parent, string key) =>
        OptionalLong(parent, key)
        ?? throw new SongManifestFormatException($"\"{key}\" is required and must be a number.");
}
