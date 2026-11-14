import SwiftUI

struct TitlesView: View {
    @EnvironmentObject var store: AppState
    private var accent: Color { store.accent.color }

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text("头衔").font(.system(size: 22, weight: .bold))
                Spacer()
            }
            .padding(.top, 6)

            VStack(spacing: 0) {
                InfoRow(label: "当前头衔", value: "—")
                InfoRow(label: "可用头衔", value: "—")
            }
            .padding(.horizontal, 16)
            .background(DS.card)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))

            Text("头衔数据来自 LinuxTitle，装了才能在 App 里换")
                .font(.system(size: 12)).foregroundColor(DS.muted)
                .frame(maxWidth: .infinity, alignment: .leading)

            Spacer(minLength: 78)
        }
        .padding(.horizontal, 16)
    }
}
