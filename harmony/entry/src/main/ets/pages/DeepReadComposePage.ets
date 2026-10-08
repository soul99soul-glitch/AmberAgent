import { DeepReadComposerSheet } from '../components/deepread/DeepReadComposerSheet.ets';

// Route wrapper and discovery/library sheets share one input and persistence owner.
@Entry
@Component
struct DeepReadComposePage {
  build(): void {
    Column() { DeepReadComposerSheet() }.width('100%').height('100%')
  }
}
