## KaTeX — Block Math

Block (display) math uses double dollar signs: $$...$$. It renders the expression centered on its own line, typically at larger size than inline math.

### Quadratic Formula

The solutions to $ax^2 + bx + c = 0$ are given by:

$$x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}$$

### Euler's Identity

One of the most beautiful equations in mathematics:

$$e^{i\pi} + 1 = 0$$

### Fourier Transform

The continuous Fourier transform of a function $f(t)$:

$$\hat{f}(\xi) = \int_{-\infty}^{\infty} f(t)\, e^{-2\pi i \xi t}\, dt$$

### Gaussian Distribution

The probability density function of the normal distribution $\mathcal{N}(\mu, \sigma^2)$:

$$p(x) = \frac{1}{\sigma\sqrt{2\pi}} \exp\!\left(-\frac{(x-\mu)^2}{2\sigma^2}\right)$$

### Matrix Multiplication

Given matrices $A \in \mathbb{R}^{m \times n}$ and $B \in \mathbb{R}^{n \times p}$, their product $C = AB \in \mathbb{R}^{m \times p}$ is:

$$C_{ij} = \sum_{k=1}^{n} A_{ik} B_{kj}$$

### Taylor Series

The Taylor series expansion of $e^x$ around $x = 0$:

$$e^x = \sum_{n=0}^{\infty} \frac{x^n}{n!} = 1 + x + \frac{x^2}{2!} + \frac{x^3}{3!} + \cdots$$

### Bayes' Theorem

$$P(A \mid B) = \frac{P(B \mid A)\, P(A)}{P(B)}$$

where $P(A \mid B)$ is the posterior probability of event $A$ given that $B$ has occurred.

### Block Math Followed by Prose

Inline math $x^2$ in the sentence before this block, then the block:

$$\nabla \cdot \mathbf{E} = \frac{\rho}{\varepsilon_0}$$

And regular prose continues here after the block — the renderer must leave no gap artefacts.
