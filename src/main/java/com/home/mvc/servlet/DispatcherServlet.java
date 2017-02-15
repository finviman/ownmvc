package com.home.mvc.servlet;

import com.home.mvc.annotation.Nullable;
import com.home.mvc.exception.TunningException;
import com.home.mvc.util.View;
import eu.infomas.annotation.AnnotationDetector;
import eu.infomas.annotation.Cursor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author liyang
 *
 */
@Slf4j
public class DispatcherServlet extends HttpServlet {

    private Map<String, Class> clazzes = new ConcurrentHashMap<>(128);//map webServlet's urlPattern to controllerClass
    private Map<String, Object> instances = new ConcurrentHashMap<>(128);//map classSimpleName to instance.
    private Map<String,Method> methods = new ConcurrentHashMap<>(256);//map className#methodName to method.
    private Map<Class, Field[]> cacheFields = new ConcurrentHashMap<>(128);//form fields cache

    public DispatcherServlet() {
        super();
    }

    @Override
    public void init() {
        try {
            List<Class<?>> controllerClasses = AnnotationDetector.scanClassPath("com.home.mvc.servlet")
                    .forAnnotations(Path.class).collect(Cursor::getType);
            for (Class<?> controllerClass : controllerClasses) {
                final Path path = controllerClass.getAnnotation(Path.class);
                log.debug("mapping url {} to controller {}", path.value(), controllerClass.getSimpleName());
                clazzes.put(path.value(), controllerClass);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

	public void doGet(HttpServletRequest req,HttpServletResponse resp){
		this.doPost(req, resp);
	}

	public void doPost(HttpServletRequest req,HttpServletResponse resp){
        String uri = req.getRequestURI();
        final String[] controllerUriAndMethod = splitStruct(uri);
        final Class controller = clazzes.get(controllerUriAndMethod[0]);
        Method method = getMethod(controllerUriAndMethod[1], controller,req.getMethod());
		if(method==null){
            getPrint(resp).print(uri+" is not find!");
            return;
        }

		try {
            Object[] args = getArgumentsJDK8(req,method);
			Object result = method.invoke(getInstance(controller), args);
			if(result instanceof View){//to some jsp display
				req.getRequestDispatcher(result.toString()).forward(req, resp);
//				resp.sendRedirect(result.toString());
			}else{//pattern Result or String, Result.toString is invoked by default;
				getPrint(resp).print(result);
			}
		} catch (Exception e) {
            getPrint(resp).print(e.getMessage());
		}
	}

    /**
     * cache controller instance, means singleton.
     * if not synchronized,maybe new more than one instance at beginning. it's OK.
     */
    private Object getInstance(Class controller) throws Exception {
        Object controllerInstance=instances.get(controller.getSimpleName());
        if (controllerInstance==null) {
            controllerInstance = controller.newInstance();
            final Field[] fields = controller.getDeclaredFields();//we need spring bean.
            final WebApplicationContext webApplicationContext = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
            for (Field field : fields) {
                if (field.isAnnotationPresent(Autowired.class)) {//by type
//                    get spring beans.
                    final Object bean = webApplicationContext.getBean(field.getType());
                    field.setAccessible(true);
                    field.set(controllerInstance,bean);
                }
            }
            instances.put(controller.getSimpleName(), controllerInstance);
        }
        return controllerInstance;
    }

    /**
     * split uri
     * @return string[0] is urlpattern point to a Controller,string[1] is the method.
     */
    private static String[] splitStruct(String url) {
        String[] controllerAndMethod = {"/",""};
        String noEndSlash=url;
        if (url.endsWith("/")) {
            noEndSlash = StringUtils.chop(url);
        }
        String[] parts = noEndSlash.split("/");
        for(int i=1;i<parts.length;i++) {
            if (i<parts.length-1) {
                controllerAndMethod[0] += parts[i] + "/";
            }else {
                controllerAndMethod[1] = parts[i];
            }
        }
        return controllerAndMethod;
    }


    protected PrintWriter getPrint(HttpServletResponse resp){
		try {
			resp.setContentType("text/html; charset=GB2312");
			resp.setCharacterEncoding("GB2312");
            return resp.getWriter();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

    /**
     * this method use new api since jdk 1.8 to identify parameter,don't forget to compile with -parameters.
	 * composite method arguments, arguments can be divide to some situation:
     * because i can not get parameter names by default.
     * TODO::add enum type
	 */
	private Object[] getArgumentsJDK8(HttpServletRequest req,Method method) throws Exception {
        final Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i=0;i<parameters.length;i++) {
            Class<?> paramType = parameters[i].getType();
            final String name = parameters[i].getName();
            if (paramType.isPrimitive()) {
                paramType = getBoxClass(paramType.getName());
            }
            if (!paramType.getName().startsWith("java.lang")) {//user defined type. POJO form.
                args[i] = parseRequest(req, paramType);
                continue;
            }
//            java official type.
            String value = req.getParameter(name);
            final boolean isNullable = parameters[i].isAnnotationPresent(Nullable.class);

            if (StringUtils.isBlank(value)) {
                if (isNullable) {
                    continue;
                }else{
                    throw new TunningException(name +" is required");
                }
            }
            value = value.trim();
            if (String.class == paramType) {
                args[i] = value;
            } else if (Integer.class == paramType) {
                args[i] = Integer.valueOf(value);
            } else if (Long.class == paramType) {
                args[i] = Long.valueOf(value);
            } else if (Float.class == paramType) {
                args[i] = Float.valueOf(value);
            } else if (Double.class == paramType) {
                args[i] = Double.valueOf(value);
            } else if (Boolean.class == paramType) {
                args[i] = Boolean.valueOf(value);
            } else if (Short.class == paramType) {
                args[i] = Short.valueOf(value);
            } else if (Character.class == paramType) {
                args[i] = value.charAt(0);
            } else if (Byte.class == paramType) {
                args[i] = Byte.valueOf(value);
            }
        }
		return args;
	}

    private Class getBoxClass(String primitive) {
        Class box;
        switch (primitive) {
            case "short":box = Short.class;break;
            case "int":box =  Integer.class;break;
            case "byte":box =  Byte.class;break;
            case "long":box =  Long.class;break;
            case "double":box =  Double.class;break;
            case "boolean":box =  Boolean.class;break;
            case "char":box =  Character.class;break;
            case "float":box =  Float.class;break;
            default:box = String.class;
        }
        return box;
    }

//	/**
//     * this method use annotation to identify parameter
//	 * composite method arguments, arguments can be divide to some situation:
//	 * 1.only a POJO form,this kind of args has no annotation.
//     * 2.some basic type like String,Long,Integer,BigDecimal,Double,Float. this args should has annotation
//     * because i can not get parameter names by default.
//	 * @return
//	 */
//	protected Object[] getArgumentsJDK7(HttpServletRequest req,Method method){
//		Class<?>[] parameterTypes = method.getParameterTypes();
//        final Annotation[][] parameterAnnotations = method.getParameterAnnotations();
//		Object[] args = new Object[parameterTypes.length];
//		args[0]=req;
//		for(int i=1;i<parameterTypes.length;i++){
//			Class paraType = parameterTypes[i];
//			if(String.class==paraType){
//				args[i] =
//			}else if{
//				;
//			}
//		}
//		return args;
//	}

	/**
     * no need to synchronized like struts1
	 */
	private Method getMethod(String methodName,Class clazz,String httpMethod) {
        String methodKey = clazz.getSimpleName()+"#"+methodName;
        Method m = methods.get(methodKey);
        if(m==null){
            Method[] all= clazz.getMethods();
            for(Method mx :all){
                mx.isAnnotationPresent(getAnnoFromRequestMethod(httpMethod));
                if(mx.getName().equals(methodName)){
                    m = mx;
                }
            }
            if (m!=null) {
                methods.put(methodKey, m);
            }
        }
        return m;
	}

    private Class<? extends Annotation> getAnnoFromRequestMethod(String httpMethod) {
        Class<? extends Annotation> methodAnno;
        switch (httpMethod) {
            case HttpMethod.GET:methodAnno = GET.class;break;
            case HttpMethod.POST:methodAnno = POST.class;break;
            case HttpMethod.DELETE:methodAnno = DELETE.class;break;
            case HttpMethod.PUT:methodAnno= PUT.class;break;
            case HttpMethod.HEAD:methodAnno= HEAD.class;break;
            case HttpMethod.OPTIONS:methodAnno= OPTIONS.class;break;
            default:methodAnno=GET.class;break;
        }
        return methodAnno;
    }

    /**
     * @param request request
     * @param tClass form class
     * @param <T> form class
     * @return the T object instantiate by request values
     * @throws Exception if the neccessary request value is null or value datatype is wrong.
     */
    private  <T> T parseRequest(HttpServletRequest request, Class<T> tClass) throws Exception {
        Field[] fields = cacheFields.get(tClass);
        if (fields == null) {
            return parseRequestNoCache(request, tClass);
        } else {
            T instance = tClass.newInstance();
            return parseRequestFromCache(request,instance,fields);
        }
    }
    /**
     * @param request request
     * @param <T>     form class
     * @return the T object instantiate by request value.
     * @throws Exception while not null param is null or datatype is wrong.
     */
    private   <T> T parseRequestFromCache(HttpServletRequest request, T instance,Field[] fields) throws Exception {
        for (Field f : fields) {
            invokeObj(instance, request, f);
        }
        return instance;
    }

    /**
     * @param request request
     * @param tClass  form class
     * @param <T>     form class
     * @return the T object instantiate by request value.
     * @throws Exception while not null param is null or datatype is wrong.
     */
    private <T> T parseRequestNoCache(HttpServletRequest request, Class<T> tClass) throws Exception {
        T instance = tClass.newInstance();
        Field[] fields = tClass.getDeclaredFields();
        for (Field f : fields) {
            invokeObj(instance, request, f);
        }
        cacheFields.put(tClass, fields);
        return instance;
    }

    private <T> void invokeObj(T instance,HttpServletRequest request,Field f) throws Exception {
        String fieldName = f.getName();
        final boolean isNullable = f.isAnnotationPresent(Nullable.class);
        String value = request.getParameter(f.getName());
        if (StringUtils.isBlank(value)) {
            if (isNullable) {
                return;
            } else {
                throw new TunningException(fieldName + " is required!");
            }
        }
        value = value.trim();
        Class<?> fieldType = f.getType();
        f.setAccessible(true);
        if (fieldType.isPrimitive()) {
            fieldType = getBoxClass(fieldType.getName());
        }
        if (fieldType == String.class) {
            f.set(instance,value);
        } else if (fieldType == Integer.class) {
            f.setInt(instance,Integer.parseInt(value));
        } else if (fieldType == Long.class) {
            f.setLong(instance,Long.parseLong(value));
        } else if (fieldType == Double.class) {
            f.setDouble(instance, Double.parseDouble(value));
        } else if (fieldType == Float.class) {
            f.setFloat(instance, Float.parseFloat(value));
        } else if (fieldType == Boolean.class) {
            f.setBoolean(instance, Boolean.parseBoolean(value));
        } else if (fieldType == Short.class) {
            f.setShort(instance, Short.parseShort(value));
        } else if (fieldType == Character.class) {
            f.setChar(instance, value.charAt(0));
        } else if (fieldType == Byte.class) {
            f.setByte(instance, Byte.parseByte(value));
        }
    }


//    /**
//     */
//    private static String createSetMethodName(String fieldName) {
//        return "set"+Character.toUpperCase(fieldName.charAt(0))+fieldName.substring(1);
//    }
}
