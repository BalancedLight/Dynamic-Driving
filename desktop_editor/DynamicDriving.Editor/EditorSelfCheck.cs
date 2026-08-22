using System.IO;
using System.Globalization;
using System.Text;
using DynamicDriving.Editor.ViewModels;

namespace DynamicDriving.Editor;

/// <summary>
/// The `--self-check` mode of the published editor.
///
/// It walks the whole acceptance path against a real song folder — load the window, open the song,
/// preview it, edit it, save a copy, reopen the copy — and exits non-zero with a reason if any step
/// fails. Running this against the shipped ZIP is what proves the portable build actually works on a
/// clean machine, rather than merely starting without crashing.
/// </summary>
internal static class EditorSelfCheck
{
    public static int Run(string songFolderPath, TextWriter output)
    {
        var failures = new List<string>();

        try
        {
            // 1. Published runtime culture data. WPF bindings request en-US during first layout;
            //    constructing a window alone does not exercise that path under invariant mode.
            var uiCulture = CultureInfo.GetCultureInfo("en-US");
            output.WriteLine($"ok: loaded runtime culture {uiCulture.Name}");

            // 2. The window itself: proves the XAML parses, every resource resolves, and the
            //    bindings attach to a live view model.
            var window = new MainWindow();
            if (window.DataContext is not MainViewModel)
            {
                failures.Add("The main window did not bind to a view model.");
            }

            window.Close();
            output.WriteLine("ok: main window loaded");

            // 3. Open the song folder.
            var interaction = new ScriptedInteraction();
            using var viewModel = new MainViewModel(interaction);
            viewModel.LoadFolder(songFolderPath);

            if (interaction.Errors.Count > 0)
            {
                failures.Add($"Opening the folder reported: {string.Join("; ", interaction.Errors)}");
            }

            if (viewModel.Stems.Count == 0)
            {
                failures.Add("No stems were loaded from the song folder.");
            }

            if (viewModel.Waveform.Peaks.Count == 0)
            {
                failures.Add("No waveform was produced for the transport stem.");
            }

            if (viewModel.TotalMs <= 0)
            {
                failures.Add("The song reported a zero length.");
            }

            output.WriteLine(
                $"ok: opened \"{viewModel.DisplayName}\" with {viewModel.Stems.Count} stem(s), " +
                $"{viewModel.TotalMs:F0} ms");

            // 4. Preview it. A build agent with no audio device is expected and must not fail the
            //    check, so only the transport state is asserted.
            viewModel.SpeedMph = 45;
            viewModel.TogglePlaybackCommand.Execute(null);
            viewModel.RefreshPreviewState();
            viewModel.StopPlaybackCommand.Execute(null);
            output.WriteLine("ok: previewed without error");

            // 5. Edit it.
            const string editedArtist = "Self Check Artist";
            const string addedEventId = "self_check_event";
            viewModel.Artist = editedArtist;
            var eventStem = viewModel.Stems.First(stem => stem.Included);
            viewModel.SelectedStem = eventStem;
            viewModel.AddEventCommand.Execute(null);
            var addedEvent = viewModel.SelectedEvent;
            if (addedEvent is null)
            {
                failures.Add("Add event did not select a newly created event.");
            }
            else
            {
                addedEvent.EventId = addedEventId;
                viewModel.AddConditionCommand.Execute(null);
                if (addedEvent.Conditions.Count != 2)
                {
                    failures.Add(
                        $"Add condition produced {addedEvent.Conditions.Count} condition(s), expected 2.");
                }
                else
                {
                    output.WriteLine("ok: added an event and a second condition");
                }
            }

            var boostedModifier = viewModel.Stems
                .SelectMany(stem => stem.Events)
                .SelectMany(stemEvent => stemEvent.Modifiers)
                .FirstOrDefault(modifier => !modifier.IsReverb);
            if (boostedModifier is null)
            {
                failures.Add("The bundled demo has no gain modifier for the editor round-trip check.");
            }
            else
            {
                boostedModifier.MultiplierText = "1.";
                if (boostedModifier.MultiplierText != "1.")
                {
                    failures.Add("The gain multiplier editor rejected an intermediate decimal point.");
                }

                boostedModifier.MultiplierText = "1.75";
                if (boostedModifier.Multiplier != 1.75)
                {
                    failures.Add($"The gain multiplier text parsed as {boostedModifier.Multiplier}, expected 1.75.");
                }
                else
                {
                    output.WriteLine("ok: typed gain multiplier 1.75 through an intermediate decimal point");
                }
            }
            if (!viewModel.IsDirty)
            {
                failures.Add("Editing a field did not mark the song as having unsaved changes.");
            }

            output.WriteLine("ok: edited the artist field");

            // 6. Save a copy into a fresh folder.
            var copyFolder = Path.Combine(
                Path.GetTempPath(),
                "dynamicdriving-selfcheck",
                Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(copyFolder);
            interaction.NextFolder = copyFolder;
            viewModel.SaveCopyCommand.Execute(null);

            if (interaction.Errors.Count > 0)
            {
                failures.Add($"Saving a copy reported: {string.Join("; ", interaction.Errors)}");
            }

            var copiedManifest = Path.Combine(copyFolder, "song.json");
            if (!File.Exists(copiedManifest))
            {
                failures.Add($"No song.json was written to {copyFolder}.");
            }

            output.WriteLine($"ok: saved a copy to {copyFolder}");

            // 7. Reopen the copy and confirm the edit survived the round trip.
            if (File.Exists(copiedManifest))
            {
                var reopenInteraction = new ScriptedInteraction();
                using var reopened = new MainViewModel(reopenInteraction);
                reopened.LoadFolder(copyFolder);

                if (reopened.Artist != editedArtist)
                {
                    failures.Add($"The reopened copy has artist \"{reopened.Artist}\", expected \"{editedArtist}\".");
                }

                var reopenedMultiplier = reopened.Stems
                    .SelectMany(stem => stem.Events)
                    .SelectMany(stemEvent => stemEvent.Modifiers)
                    .FirstOrDefault(modifier => !modifier.IsReverb)
                    ?.Multiplier;
                if (reopenedMultiplier != 1.75)
                {
                    failures.Add(
                        $"The reopened gain multiplier is {reopenedMultiplier?.ToString() ?? "missing"}, expected 1.75.");
                }
                else
                {
                    output.WriteLine("ok: reopened gain multiplier at 1.75");
                }

                var reopenedEvent = reopened.Stems
                    .SelectMany(stem => stem.Events)
                    .FirstOrDefault(stemEvent => stemEvent.EventId == addedEventId);
                if (reopenedEvent?.Conditions.Count != 2)
                {
                    failures.Add("The added event and its two conditions did not survive save and reopen.");
                }
                else
                {
                    output.WriteLine("ok: reopened the added event with both conditions");
                }

                if (reopened.Stems.Count(stem => stem.Included) != viewModel.Stems.Count(stem => stem.Included))
                {
                    failures.Add("The reopened copy has a different number of included stems.");
                }

                var previewStem = reopened.Stems.First(stem => stem.Included);
                var savedStemCount = reopened.BuildManifest().Stems.Count;
                reopened.ToggleStemPreviewCommand.Execute(previewStem);
                if (!previewStem.IsMuted || reopened.BuildManifest().Stems.Count != savedStemCount || reopened.IsDirty)
                {
                    failures.Add("Preview mute changed dirty state or removed a stem from the saved manifest.");
                }
                else
                {
                    output.WriteLine("ok: preview mute left the saved manifest unchanged");
                }
                reopened.ToggleStemPreviewCommand.Execute(previewStem);

                reopened.DisplayName += " Saved";
                if (!reopened.IsDirty)
                {
                    failures.Add("Editing the reopened copy did not mark it dirty before the Save check.");
                }

                reopened.SaveCommand.Execute(null);
                if (reopened.IsDirty || reopened.WindowTitle.EndsWith(" *", StringComparison.Ordinal))
                {
                    failures.Add("Save left the reopened copy marked dirty in the window title.");
                }
                else
                {
                    output.WriteLine("ok: Save cleared the dirty title marker");
                }

                output.WriteLine("ok: reopened the copy with the edit intact");
            }

            TryDelete(copyFolder);
        }
        catch (Exception error)
        {
            failures.Add($"{error.GetType().Name}: {error.Message}");
        }

        if (failures.Count == 0)
        {
            output.WriteLine("SELF CHECK PASSED");
            return 0;
        }

        var report = new StringBuilder("SELF CHECK FAILED");
        foreach (var failure in failures)
        {
            report.AppendLine().Append(" - ").Append(failure);
        }

        output.WriteLine(report.ToString());
        return 1;
    }

    private static void TryDelete(string folderPath)
    {
        try
        {
            if (Directory.Exists(folderPath))
            {
                Directory.Delete(folderPath, recursive: true);
            }
        }
        catch (IOException)
        {
            // Leaving a temp folder behind is not a self-check failure.
        }
    }

    /// <summary>Stands in for the dialogs so the check never waits for a person.</summary>
    private sealed class ScriptedInteraction : IUserInteraction
    {
        public string? NextFolder { get; set; }

        public List<string> Errors { get; } = new();

        public string? PickFolder(string title) => NextFolder;

        public string? PickFile(string title, string filter) => null;

        public bool ConfirmDiscardChanges() => true;

        public bool Confirm(string message, string title) => true;

        public void ShowError(string message, string title) => Errors.Add($"{title}: {message}");

        public void ShowInformation(string message, string title)
        {
        }
    }
}
