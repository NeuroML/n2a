## Overview ##

"Neurons to Algorithms" (N2A) is a language for modeling neural systems, along with a software tool for editing models and simulating them. For an introduction to the concepts behind N2A, see the paper [N2A: a computational tool for modeling from neurons to algorithms](http://www.frontiersin.org/Neural_Circuits/10.3389/fncir.2014.00001/abstract). For the current definition of the language, see the [Language Reference](https://github.com/frothga/n2a/wiki/Language) page on our wiki.

N2A conceives of each neural component ("part" or "model") as a bundle of attributes, which include constants and equations. All attributes and dynamics are name-value pairs. In the case of equations, the name is a variable and the value tells how that variable relates to other variables and changes through time.

Because models are specified as data rather than code (declarative rather than imperative), it is easy for one model to inherit from another and extend it. In particular, there is no distinction between part definitions (such as an Izhikevich Neuron) and parameters used in a specific configuration. You simply inherit the model and make any necessary changes.

A model may contain other models. For example, a cerebellum model may contain population models for Purkinje cells, inferior-olive cells, and so on. The cerebellum model could be further incorporated into a model of smooth pursuit involving multiple brain regions. The goal of N2A, of course, is to eventually model the entire brain.

Some things N2A is NOT:

  * Not a simulator. Rather, the tool compiles the language for a given target (NEURON, NEST, C++, Python, etc.).
  * Not deep learning, nor machine learning in general. The goal of N2A is to build an integrated understanding of the whole brain, in a form that can be both analyzed and computed.

## Download ##

[Installation](https://github.com/frothga/n2a/wiki/Installation) -- How to download and install N2A, and set up at least one simulator.

[Getting Started](https://github.com/frothga/n2a/wiki/Getting%20Started) -- Run a simple "Hello World" example: the Hodgkin-Huxley cable equations.

[Contributing Code](https://github.com/frothga/n2a/wiki/Developer%20HowTo) -- How to set up a development environment, if you would like to volunteer on this project or simply wish to build from source.

## Documentation ##

The [wiki](https://github.com/sandialabs/n2a/wiki) contains language documentation plus several guides and other information about the software package.
