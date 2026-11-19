import SwiftUI
import Shared

struct RouteView: UIViewControllerRepresentable {
    let shell: IosShell
    let route: AppRoute
    let onOpen: (AppRoute) -> Void
    let onExit: () -> Void
    let onBack: () -> Void
    let onRestart: (AppRoute) -> Void
    let onBlockBack: (Bool) -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        shell.controller(route: route, onOpen: onOpen, onExit: onExit, onBack: onBack, onRestart: onRestart, onBlockBack: { onBlockBack($0.boolValue) })
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

// iOS 앱 틀. iOS 플랫폼 코드는 이 파일 하나다. 탭, 내비게이션 바, 뒤로가기는 Kotlin 의 화면 경로 표(AppRoute)가 정한다.
struct TabRoot: View {
    let shell: IosShell
    let root: AppRoute
    @Binding var path: [AppRoute]
    var onRestart: (AppRoute) -> Void = { _ in }
    /** 이 탭의 화면 하나라도 처리 중인지. 앱 틀이 받아 그동안 탭 전환을 막는다. */
    var onBusy: (Bool) -> Void = { _ in }
    /** 처리 중이라 뒤로 버튼과 가장자리 스와이프를 숨긴 화면(키 확인 중). */
    @State private var backBlocked: Set<AppRoute> = []

    var body: some View {
        NavigationStack(path: $path) {
            destination(root)
                .navigationDestination(for: AppRoute.self) { destination($0) }
        }
    }

    private func destination(_ route: AppRoute) -> some View {
        RouteView(shell: shell, route: route, onOpen: { path.append($0) }, onExit: { path.removeAll() }, onBack: { path.removeLast() }, onRestart: onRestart, onBlockBack: { blocked in
            if blocked { backBlocked.insert(route) } else { backBlocked.remove(route) }
            onBusy(blocked)
        })
            // 한 번에 빼고 쌓으면(연습의 Next, 시험 끝) SwiftUI 가 같은 칸의 RouteView 를 다시 쓴다. Compose 화면은 만들 때 한 번만 정하므로
            // 경로를 identity 로 줘서 경로가 바뀌면 화면을 새로 만든다. 없으면 탭만 바뀌고 앞 화면이 남는다(#134).
            .id(route)
            // 배경은 안전 영역 밖까지 그리고, 내용은 Compose 화면이 safeDrawing 인셋으로 안쪽에 둔다.
            .ignoresSafeArea()
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            // 뒤로 버튼을 숨기면 가장자리 스와이프도 꺼진다. 시험, 연습, 설정의 서베이는 화면 안 X 로만 나가고, 키 확인 중에는 끝날 때까지 못 나간다.
            .navigationBarBackButtonHidden(!route.showsBackButtonOnIos || backBlocked.contains(route))
            .toolbar(route.showsBackButtonOnIos ? .visible : .hidden, for: .navigationBar)
            // 보일 때 .visible 을 주면 첫 화면 설정이 이겨 시험 화면에도 탭이 남는다. .automatic 이어야 숨는다.
            .toolbar(shell.showsTabBar(route: route) ? .automatic : .hidden, for: .tabBar)
    }
}

// 시작 분기(S0, F1.0)가 첫 화면을 정할 때까지 실행 화면과 같은 빈 배경만 둔다. 키 입력과 동의는 탭 없이 혼자 뜨고, 마치면 분기를 다시 한다.
struct ContentView: View {
    let shell: IosShell
    @State private var start: AppRoute?
    @State private var startPath: [AppRoute] = []

    var body: some View {
        if let first = start, first == AppRoute.ExamTab.shared {
            Tabs(shell: shell)
        } else if let first = start {
            TabRoot(shell: shell, root: first, path: $startPath, onRestart: { next in
                startPath = []
                start = next
            })
            // 루트가 바뀌면 그 Compose 화면을 새로 만든다.
            .id(first)
        } else {
            Color(.systemBackground)
                .ignoresSafeArea()
                .task { shell.decideStart { start = $0 } }
        }
    }
}

struct Tabs: View {
    let shell: IosShell
    @State private var tab: AppRoute = AppRoute.ExamTab.shared
    @State private var paths: [AppRoute: [AppRoute]] = [:]
    /** 키 확인처럼 처리 중인 화면이 있다. 그동안 탭 바를 눌러도 탭이 바뀌지 않는다. */
    @State private var busy = false

    var body: some View {
        TabView(selection: $tab) {
            ForEach(AppRoute.companion.tabs, id: \.self) { root in
                Tab(root.title, systemImage: icon(root), value: root) {
                    TabRoot(shell: shell, root: root, path: Binding(
                        get: { paths[root] ?? [] },
                        set: { paths[root] = $0 }
                    ), onBusy: { busy = $0 })
                }
            }
        }
        .onChange(of: busy) { _, busy in setTabBarEnabled(!busy) }
        // 분석 끝 알림을 눌렀으면 모의고사 탭 위에 그 리포트를 연다(#229).
        .onChange(of: RouteOpener.shared.pending, initial: true) { _, route in
            guard let route else { return }
            tab = AppRoute.ExamTab.shared
            paths[AppRoute.ExamTab.shared] = [route]
            RouteOpener.shared.pending = nil
        }
    }

    /**
     * 처리 중에는 탭 바가 눌리지 않게 한다. 탭을 옮기면 처리 중인 화면이 가려져 결과를 놓친다.
     * 선택 바인딩에서 값을 무시해도 UIKit 탭 바는 먼저 탭을 바꿔 버려서 탭 바 자체의 입력을 끈다.
     */
    private func setTabBarEnabled(_ enabled: Bool) {
        func find(_ controller: UIViewController?) -> UITabBarController? {
            guard let controller else { return nil }
            if let tabs = controller as? UITabBarController { return tabs }
            return controller.children.lazy.compactMap { find($0) }.first ?? find(controller.presentedViewController)
        }
        let roots = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).map(\.rootViewController)
        roots.compactMap { find($0) }.forEach { $0.tabBar.isUserInteractionEnabled = enabled }
    }

    private func icon(_ tab: AppRoute) -> String {
        switch tab {
        case AppRoute.PracticeTab.shared: "mic"
        case AppRoute.ExpressionsTab.shared: "text.bubble"
        case AppRoute.History.shared: "calendar"
        case AppRoute.Settings.shared: "gearshape"
        default: "doc.text"
        }
    }
}
