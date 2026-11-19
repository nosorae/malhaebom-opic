import SwiftUI
import WidgetKit

/**
 * 오늘 복습 위젯(F19.6). 앱이 App Group 저장소에 써 둔 장면을 시각 순서대로 보인다(F19.7).
 * 장면 계산과 문구는 앱(Kotlin `IosShell.watchReviewWidget`)이 하고, 여기는 읽어서 그리기만 한다. 누르면 복습 링크로 앱이 열린다.
 */
@main
struct ReviewWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ReviewWidget", provider: ReviewTimeline()) { scene in
            ReviewWidgetView(scene: scene)
        }
        .configurationDisplayName("오늘 복습")
        .description("오늘 남은 복습과 이번 주 복습한 날")
        .supportedFamilies([.systemSmall])
    }
}

/** 장면 하나. 앱이 쓴 JSON 의 이름(date 는 초)을 그대로 쓴다. */
struct ReviewScene: TimelineEntry, Decodable {
    let date: Date
    let title: String
    let text: String
}

struct ReviewTimeline: TimelineProvider {
    /** 앱과 같은 저장소 이름과 키. 앱 쪽 값은 Kotlin `IosShell.kt` 의 APP_GROUP, WIDGET_KEY 다. */
    private let defaults = UserDefaults(suiteName: "group.io.github.nosorae.aiopic")

    /** 위젯 고르기 화면과 시스템이 글자를 가리는 자리표시에서만 보이는 견본. 실제 위젯에는 쓰지 않는다. */
    private let sample = ReviewScene(date: .now, title: "오늘 복습 6장", text: "이번 주 3일 복습")

    /** 앱을 아직 열지 않아 앱이 쓴 장면이 없을 때 보이는 문구. 지어낸 숫자를 보이지 않는다. */
    private let empty = ReviewScene(date: .now, title: "오늘 복습", text: "앱을 열면 오늘 복습이 보여요")

    func placeholder(in context: Context) -> ReviewScene { sample }

    func getSnapshot(in context: Context, completion: @escaping (ReviewScene) -> Void) {
        completion(context.isPreview ? sample : scenes().last { $0.date <= .now } ?? empty)
    }

    // 앱이 값을 바꿀 때마다 다시 그리라고 알리므로 시스템이 따로 다시 묻지 않게 한다.
    // 장면이 없을 때도 앱을 처음 열면 앱이 장면을 쓰고 다시 그리라고 알린다.
    func getTimeline(in context: Context, completion: @escaping (Timeline<ReviewScene>) -> Void) {
        let scenes = scenes()
        completion(Timeline(entries: scenes.isEmpty ? [empty] : scenes, policy: .never))
    }

    private func scenes() -> [ReviewScene] {
        guard let json = defaults?.string(forKey: "review_widget")?.data(using: .utf8) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return (try? decoder.decode([ReviewScene].self, from: json)) ?? []
    }
}

struct ReviewWidgetView: View {
    let scene: ReviewScene

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(scene.title)
                .font(.headline)
            Text(scene.text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .containerBackground(.background, for: .widget)
        .widgetURL(URL(string: "malhaebom://review"))
    }
}
