using System.IO;
using System.Runtime.InteropServices;
using System.Windows;

namespace DynamicDriving.Editor;

public partial class App : Application
{
    private const string SelfCheckSwitch = "--self-check";
    private const int AttachParentProcess = -1;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // An unhandled exception on the UI thread would otherwise take the editor down mid-edit.
        DispatcherUnhandledException += (_, args) =>
        {
            MessageBox.Show(
                args.Exception.Message,
                "Dynamic Driving Editor",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            args.Handled = true;
        };

        var selfCheckIndex = Array.IndexOf(e.Args, SelfCheckSwitch);
        if (selfCheckIndex < 0)
        {
            new MainWindow().Show();
            return;
        }

        RunSelfCheck(selfCheckIndex, e.Args);
    }

    /// <summary>
    /// Headless acceptance run: no window is shown and the process exit code is the result.
    /// </summary>
    private void RunSelfCheck(int selfCheckIndex, string[] args)
    {
        // A WinExe has no console of its own, so borrow the one that launched it. Without this the
        // self-check would run silently and CI would only see the exit code.
        AttachConsole(AttachParentProcess);

        ShutdownMode = ShutdownMode.OnExplicitShutdown;
        var songFolder = selfCheckIndex + 1 < args.Length ? args[selfCheckIndex + 1] : null;

        if (string.IsNullOrWhiteSpace(songFolder) || !Directory.Exists(songFolder))
        {
            Console.Out.WriteLine($"Usage: DynamicDriving.Editor.exe {SelfCheckSwitch} <song-folder>");
            Console.Out.Flush();
            Shutdown(2);
            return;
        }

        var exitCode = EditorSelfCheck.Run(songFolder, Console.Out);
        Console.Out.Flush();
        Shutdown(exitCode);
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AttachConsole(int processId);
}
