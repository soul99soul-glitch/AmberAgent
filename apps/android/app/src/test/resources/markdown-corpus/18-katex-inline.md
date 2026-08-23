## KaTeX — Inline Math

Inline math is delimited by single dollar signs: $...$. The pre-processor converts the original source to this form before it reaches the Markdown parser, so these samples use the post-preprocess `$...$` syntax directly.

### Physics

Einstein's famous mass-energy equivalence is $E = mc^2$, where $E$ is energy in joules, $m$ is mass in kilograms, and $c \approx 3 \times 10^8\ \text{m/s}$ is the speed of light.

The Schrödinger equation describes how the quantum state of a system evolves: $i\hbar \frac{\partial}{\partial t} \Psi = \hat{H} \Psi$.

### Calculus

The derivative of $f(x) = x^n$ is $f'(x) = nx^{n-1}$ by the power rule.

The fundamental theorem of calculus connects differentiation and integration: $\int_a^b f'(x)\,dx = f(b) - f(a)$.

### Statistics

The expected value of a random variable $X$ is $\mathbb{E}[X] = \sum_{i} x_i \cdot P(X = x_i)$ for a discrete distribution.

The standard normal variable is $Z = \frac{X - \mu}{\sigma}$, where $\mu$ is the mean and $\sigma$ is the standard deviation.

### Combinatorics

The number of ways to choose $k$ items from $n$ is $\binom{n}{k} = \frac{n!}{k!(n-k)!}$.

### Algorithm Analysis

Quick sort has an average-case time complexity of $O(n \log n)$ and a worst-case of $O(n^2)$. Its space complexity is $O(\log n)$ due to the recursion stack.

Binary search runs in $O(\log n)$ time and $O(1)$ space for the iterative implementation.

### Mixed Prose and Math

When you increase the learning rate $\alpha$ in gradient descent, each parameter update $\theta \leftarrow \theta - \alpha \nabla_\theta J(\theta)$ takes a larger step. Too large an $\alpha$ causes oscillation; too small slows convergence.
