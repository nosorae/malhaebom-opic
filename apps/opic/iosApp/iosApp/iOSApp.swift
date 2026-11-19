import BackgroundTasks
import SwiftUI
import Shared
import UserNotifications

@main
struct iOSApp: App {
    // 그래프(DB, 분석 대기열)는 앱에 하나여야 한다. 화면 구조체는 여러 번 다시 만들어지므로 앱이 한 번만 만든다.
    private let shell = IosShell()
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        AppDelegate.shell = shell
    }

    var body: some Scene {
        WindowGroup {
            ContentView(shell: shell)
                // 복습 링크(malhaebom://review)로 열면 복습 화면을 연다(F19.5).
                .onOpenURL { url in
                    if let route = ReviewReminderKt.routeFromLink(link: url.absoluteString) { RouteOpener.shared.pending = route }
                }
        }
    }
}

// 앱이 꺼진 사이 녹음 분석 전송이 끝나면 시스템이 앱을 뒤에서 깨워 결과를 넘긴다(#216). 결과를 다 받으면 처리기를 불러 알린다.
// 분석 끝 알림을 누르면 그 리포트를 열고, iOS 26 이상은 분석 진행을 시스템 진행 표시에 띄운다(#229).
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    static var shell: IosShell?
    private var continued: AnyObject?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        guard let shell = AppDelegate.shell else { return true }
        if #available(iOS 26.0, *) {
            let task = ContinuedAnalysis(shell: shell)
            continued = task
            shell.watchProgress { task.update($0) }
        } else {
            // 26 미만은 진행 표시 없이 지금처럼 백그라운드 처리 작업으로 비운다. 알림 권한 요청만 같은 때 한다.
            shell.watchProgress { _ in }
        }
        return true
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        AppDelegate.shell?.whenUploadsDelivered(done: completionHandler)
    }

    // 분석 끝 알림은 리포트 경로를, 복습 알림은 복습 링크를 싣는다(F19.5).
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let route = (info[IosShellKt.ROUTE_KEY] as? String).flatMap({ AnalysisNoticeKt.routeFromJson(json: $0) })
            ?? (info[IosShellKt.LINK_KEY] as? String).flatMap({ ReviewReminderKt.routeFromLink(link: $0) }) else { return }
        await MainActor.run { RouteOpener.shared.pending = route }
    }
}

/** 알림이나 링크로 열 화면. 앱이 꺼져 있었으면 탭이 뜰 때까지 남겨 두었다가 연다. */
@Observable
final class RouteOpener {
    static let shared = RouteOpener()
    var pending: AppRoute?
}

/**
 * 분석 진행을 시스템 진행 표시(잠금화면, 다이내믹 아일랜드)에 띄운다(iOS 26 이상, #229). 앱이 화면에 있을 때 기다리는 분석이 생기면 요청하고,
 * 다 끝나면 성공으로 닫는다. 사용자가 거기서 멈추면 남은 분석을 취소로 끝낸다. 비우기 자체는 지금처럼 분석 대기열이 한다.
 */
@available(iOS 26.0, *)
final class ContinuedAnalysis {
    private let shell: IosShell
    private var task: BGContinuedProcessingTask? {
        // 진행 표시가 떠 있는 동안은 뒤로 간 앱의 30초가 끝나도 비우기를 끊지 않는다.
        didSet { shell.continuing = task != nil }
    }
    private var latest: ProgressNotice?
    private var submitted = false

    init(shell: IosShell) {
        self.shell = shell
    }

    func update(_ progress: ProgressNotice?) {
        latest = progress
        guard let progress else {
            task?.setTaskCompleted(success: true)
            task = nil
            submitted = false
            return
        }
        if let task { return show(progress, on: task) }
        // 시스템은 사용자가 앱을 보고 있을 때 시작한 일만 이어 준다.
        guard !submitted, UIApplication.shared.applicationState == .active else { return }
        // 같은 식별자를 두 번 등록할 수 없어 요청마다 새 식별자를 쓴다. Info.plist 에는 이 앞부분이 와일드카드로 있다.
        let identifier = "io.github.nosorae.aiopic.progress.\(UUID().uuidString)"
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { [weak self] task in
            guard let self, let task = task as? BGContinuedProcessingTask, let latest = self.latest else {
                task.setTaskCompleted(success: true)
                return
            }
            self.task = task
            task.expirationHandler = { [weak self] in
                DispatchQueue.main.async {
                    self?.shell.cancelAnalysis()
                    self?.task = nil
                    self?.submitted = false
                    task.setTaskCompleted(success: false)
                }
            }
            self.show(latest, on: task)
        }
        let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: progress.title, subtitle: progress.text)
        // 바로 시작하지 못하면 기다리지 않는다. 진행 표시가 없어도 분석은 대기열이 한다.
        request.strategy = .fail
        submitted = (try? BGTaskScheduler.shared.submit(request)) != nil
    }

    private func show(_ progress: ProgressNotice, on task: BGContinuedProcessingTask) {
        task.progress.totalUnitCount = Int64(progress.total)
        task.progress.completedUnitCount = Int64(progress.done)
        task.updateTitle(progress.title, subtitle: progress.text)
    }
}

// 기기를 흔들면 디버깅 화면을 연다. 시뮬레이터의 흔들기(Device > Shake)는 가속도 센서가 아니라 UIKit 이벤트라서 창에서 받는다.
// 릴리스 빌드는 빈 모듈이라 아무 일도 안 한다.
extension UIWindow {
    open override func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        super.motionEnded(motion, with: event)
        if motion == .motionShake { IosShellKt.openDebugTools() }
    }
}
